// A rescue-beam path is a shared_ptr parent chain. Releasing a long one must not recurse once per action:
// a streak-mode Darkling root overflowed the ~3 MB stack of a Windows search thread (2026-10-28 export).
#include <iostream>
#include <stdexcept>
#include "combat/BattleContext.h"
#include "game/GameContext.h"
#include "sim/search/BattleScumSearcher2.h"

using namespace sts;
#include "../../../../overlay/loop_beam.h"

static void check(bool value, const char *why) {
    if (!value) throw std::runtime_error(why);
}

int main() {
    constexpr int length = 1000000;  // far beyond a 1 MB main-thread stack if released recursively
    std::shared_ptr<const loop_beam::Path> path;
    for (int i = 0; i < length; ++i) path = loop_beam::makePath(std::move(path), loop_beam::Step{static_cast<unsigned>(i)});
    check(path->length == length, "path length");
    // A prefix still owned elsewhere survives the release of its longer branch.
    const auto prefix = path->parent->parent;
    path.reset();
    check(prefix->length == length - 2 && prefix->bits == static_cast<unsigned>(length - 3), "shared prefix kept");
    const auto actions = loop_beam::actions(prefix);
    check(actions.size() == static_cast<std::size_t>(length - 2) && actions.front() == 0, "prefix chain intact");
    std::cout << "PASS: released a " << length << "-action beam path without recursion; shared prefix kept\n";
}
