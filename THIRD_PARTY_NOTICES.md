# Third-party notices

| Component | Source and pinned version | License |
| --- | --- | --- |
| Simulation core and search | [sts_lightspeed](https://github.com/gamerpuppy/sts_lightspeed), patch base `7476a81954020087da31d41d16fddf475746ec2d` | MIT; copyright 2021 gamerpuppy |
| Modified combat4r, bindings and import bridge | [sts-ironclad-agent](https://github.com/destire-mio/sts-ironclad-agent), `f8486daf6e404cf3a08c78b9c385db2a143559ea` | MIT; retain parent LICENSE |
| CommunicationMod, solver package only | [CommunicationMod](https://github.com/ForgottenArbiter/CommunicationMod), `5e417eb189530986b9047a3c9426889fb261d146` | MIT; copyright 2019 ForgottenArbiter |
| nlohmann/json header | [json](https://github.com/nlohmann/json), bundled header 3.10.2 | MIT; notice included in header |

The user supplies Slay the Spire, ModTheSpire and BaseMod. The source export does not redistribute the original game's JAR, fonts, assets or Java sources. SuperFastMode is optional and is obtained separately from its author. pybind11 is installed as a build dependency and is not bundled here.

Solver-specific source transformations are in `overlay/build_native.py`; the standalone simulator export contains the base combat4r core. Preserve upstream attribution when moving files between packages. The repository's MIT grant applies to original code and original accompanying documentation, not private recordings, third-party transcripts or experimental datasets.
