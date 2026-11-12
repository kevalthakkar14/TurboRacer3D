TURBO RACER 3D - STARTER PROJECT

Requirements:
- JDK 17 or newer (JDK 21 recommended for a stable setup)
- Internet connection for the first Gradle dependency download

Open this folder in VS Code.

Run in the VS Code terminal (PowerShell):
  gradle run

If Gradle is not on PATH, use your installed Gradle executable, for example:
  & "C:\path\to\gradle\bin\gradle.bat" run

Controls:
- W / Up Arrow: accelerate
- S / Down Arrow: brake
- A / Left Arrow: steer left
- D / Right Arrow: steer right
- Space: nitro

CITY (GTA-style look, all generated in code - no extra downloads needed):
- Textured worn asphalt with patches and manholes, double yellow centre lines, white edge lines,
  8-stripe zebra crossings and stop lines
- Raised pavement with kerbs, street lamps (glow + light pools on the road at night), trees, benches,
  bus stops, hydrants, bins
- Four kinds of block: normal city, park (grass, paths, fountain, trees), car park (painted bays and
  parked cars) and plaza (paving, fountain, planters)
- Walking pedestrians on the pavements, bloom glow at night
- Tuning constants are at the top of CarRacingGame.java (ENABLE_BLOOM, PED_COUNT, zone odds in buildCell)
- Procedural textures: Textures.java.  Batched geometry helper: MeshBatch.java

Cars are still the Kenney CarKit models. For more realistic cars drop a .glb into
src/main/resources/Models/CarKit/ and add a CarSpec line (player) or a name in TRAFFIC_MODELS (AI).
The Kenney city kit ZIP is not included; copy its extracted model files into
src/main/resources/Models after transferring/downloading the asset pack.
