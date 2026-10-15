"""Reference calibration adapter for a headless-engine text CLI (cli.sh-style).

    python tools/truth_calibration/run.py fixtures/calibration --adapter \
      "python tools/truth_calibration/headless_adapter.py --cli './cli.sh data {saves}' --profile-template <dir>"

Speaks the stdio protocol of docs/truth-calibration.md. Every "new" starts a fresh CLI process on a
fresh profile directory ({saves} in --cli), because the profile's run history changes later Neow
offers. An action is executed only if the CLI's own `actions` list offers it, through `step <i>`;
each reply's observation is `state` mapped by headless_projection.py. The CLI's `state` must carry
the engine extension described in the doc; without it the reply names the missing field.
"""
import argparse
import json
import os
from pathlib import Path
import queue
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading

sys.path.insert(0, str(Path(__file__).resolve().parent))
from headless_projection import project  # noqa: E402

PROMPT = '> '
# Schema action kind -> CLI command. "play" is 1-based on the CLI and 0-based in the schema.
COMMANDS = {
    'neow': 'neow {index}', 'event': 'event {index}', 'match': 'match {index}', 'map': 'map {x} {y}',
    'end': 'end', 'discard_potion': 'discardPotion {slot}', 'reward': 'reward {index}',
    'open_card': 'openCard {index}', 'pick': 'pick {index}', 'skip': 'skip', 'bowl': 'bowl',
    'proceed': 'proceed', 'grid': 'grid {index}', 'grid_confirm': 'gridConfirm',
    'hand_select': 'hand {index}', 'hand_confirm': 'handConfirm', 'rest': 'rest {index}', 'chest': 'chest',
    'boss_relic': 'bossRelic {index}', 'skip_boss_relic': 'skipBossRelic', 'shop': 'shop',
    'buy_card': 'buyCard {index}', 'buy_relic': 'buyRelic {index}', 'buy_potion': 'buyPotion {index}',
    'purge': 'purge {index}', 'leave': 'leave',
}
FAILURE = re.compile(r'^(ERROR:|bad |usage:|unknown command)', re.M)


class Rejected(Exception):
    pass


class Cli:
    """One CLI process; output of a command is everything up to the next prompt."""

    def __init__(self, command, template, unlock_all, timeout):
        self.command, self.template, self.unlock_all, self.timeout = command, template, unlock_all, timeout
        self.process = self.saves = None

    def start(self):
        self.stop()
        self.saves = tempfile.mkdtemp(prefix='sts1-calibration-profile-')
        if self.template:
            shutil.copytree(self.template, self.saves, dirs_exist_ok=True)
        self.process = subprocess.Popen(self.command.replace('{saves}', self.saves), shell=True,
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, bufsize=0,
                                        start_new_session=os.name == 'posix')
        self.chunks = queue.Queue()
        stdout = self.process.stdout

        def read():
            while chunk := stdout.read(65536):  # unbuffered: returns what is available
                self.chunks.put(chunk)
            self.chunks.put(None)

        threading.Thread(target=read, daemon=True).start()
        self.read()  # banner
        if self.unlock_all:
            self.send('unlockAll')

    def read(self):
        buffer = b''
        while not buffer.endswith(PROMPT.encode()):
            try:
                chunk = self.chunks.get(timeout=self.timeout)
            except queue.Empty:
                raise Rejected(f'CLI gave no prompt within {self.timeout} s')
            if chunk is None:
                raise Rejected('CLI exited')
            buffer += chunk
        return buffer[:-len(PROMPT)].decode('utf-8', 'replace')

    def send(self, line):
        try:
            self.process.stdin.write((line + '\n').encode())
            self.process.stdin.flush()
        except OSError:
            raise Rejected('CLI exited')
        return self.read()

    def stop(self):
        process, self.process = self.process, None
        if process is not None:
            try:
                process.stdin.write(b'quit\n')
                process.stdin.close()
                process.wait(timeout=3)
            except (OSError, subprocess.TimeoutExpired):
                if os.name == 'posix':
                    os.killpg(process.pid, signal.SIGKILL)
                else:
                    process.kill()
                process.wait()
        if self.saves:
            shutil.rmtree(self.saves, ignore_errors=True)
            self.saves = None


def command_for(action, state):
    """The CLI command for a schema action, after checking any card ID the action names."""
    kind = action.get('kind')
    if kind == 'play':
        hand = (state.get('player') or {}).get('hand') or []
        if not 0 <= action['hand'] < len(hand) or hand[action['hand']].get('id') != action['card']:
            raise Rejected(f'illegal action: hand index {action["hand"]} does not hold {action["card"]}')
        return f'play {action["hand"] + 1}' + (f' {action["target"]}' if 'target' in action else '')
    if kind == 'potion':
        return f'potion {action["slot"]}' + (f' {action["target"]}' if 'target' in action else '')
    if kind == 'pick':
        options = state.get('cardRewardOptions') or []
        if not 0 <= action['index'] < len(options) or options[action['index']].get('id') != action['card']:
            raise Rejected(f'illegal action: card reward {action["index"]} is not {action["card"]}')
    if kind not in COMMANDS:
        raise Rejected(f'unknown action kind {kind!r}')
    return COMMANDS[kind].format(**action)


def offered(listing):
    """Executable commands of `actions` output, by index."""
    found = {}
    for line in listing.splitlines():
        match = re.match(r'\s*(\d+): (.*)$', line)
        if match:
            found[match.group(2).split(' # ', 1)[0].strip()] = int(match.group(1))
    return found


def checked(output):
    failure = FAILURE.search(output)
    if failure:
        raise Rejected(output[failure.start():].splitlines()[0])
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--cli', required=True, help='command that starts the CLI; {saves} becomes the profile dir')
    parser.add_argument('--profile-template', help='profile directory copied into every fresh {saves}')
    parser.add_argument('--unlock-all', action='store_true', help='send unlockAll after the CLI starts')
    parser.add_argument('--timeout', type=float, default=60, help='seconds to wait for each CLI prompt')
    args = parser.parse_args()
    cli, state = Cli(args.cli, args.profile_template, args.unlock_all, args.timeout), None
    try:
        for line in sys.stdin:
            request = json.loads(line)
            if request.get('op') == 'quit':
                break
            try:
                if request.get('op') == 'new':
                    cli.start()
                    checked(cli.send(f'new {request["seed"]} {request["character"]} {request["ascension"]}'))
                elif request.get('op') == 'act' and state is not None:
                    command = command_for(request['action'], state)
                    index = offered(cli.send('actions')).get(command)
                    if index is None:
                        raise Rejected(f'illegal action: the CLI does not offer {command!r}')
                    checked(cli.send(f'step {index}'))
                else:
                    raise Rejected(f'unexpected request {request.get("op")!r}')
                state = json.loads(checked(cli.send('state')))
                reply = {'ok': True, 'observation': project(state)}
            except (Rejected, KeyError, TypeError, ValueError) as error:
                reply = {'ok': False, 'error': str(error)}
            print(json.dumps(reply, ensure_ascii=False), flush=True)
    finally:
        cli.stop()


if __name__ == '__main__':
    main()
