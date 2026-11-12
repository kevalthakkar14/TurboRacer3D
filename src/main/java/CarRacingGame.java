import com.jme3.anim.AnimComposer;
import com.jme3.anim.SkinningControl;
import com.jme3.app.SimpleApplication;
import com.jme3.asset.ModelKey;
import com.jme3.bounding.BoundingBox;
import com.jme3.effect.ParticleEmitter;
import com.jme3.effect.ParticleMesh;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.MouseAxisTrigger;
import com.jme3.input.controls.MouseButtonTrigger;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.light.SpotLight;
import com.jme3.material.MatParam;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.BloomFilter;
import com.jme3.post.filters.FogFilter;
import com.jme3.post.ssao.SSAOFilter;
import com.jme3.renderer.queue.RenderQueue.ShadowMode;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.plugins.gltf.GlbLoader;
import com.jme3.scene.plugins.gltf.GltfLoader;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import com.jme3.shadow.DirectionalLightShadowRenderer;
import com.jme3.shadow.EdgeFilteringMode;
import com.jme3.system.AppSettings;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

/**
 * Turbo Racer 3D v3 - free-driving city.
 *  - endless grid city (roads, blocks, buildings are generated around you)
 *  - you steer by heading: turn at any intersection, U-turn, drive anywhere
 *  - AI traffic follows the road grid, turns right at corners, obeys traffic lights
 *  - buildings are solid, traffic lights work, day / night + headlights
 *  - missions: drive to the glowing beacons before time runs out (or free roam)
 */
public class CarRacingGame extends SimpleApplication implements ActionListener, AnalogListener {

    // ---------------------------------------------------------------- world layout
    private static final float P = 80f;               // distance between intersection centres
    private static final float ROAD_HALF = 6f;        // road is 12 wide, one lane each way
    private static final float LANE = 3f;             // lane centre offset from road centre
    private static final float STOP_DIST = 8.5f;      // stop line distance from intersection centre
    private static final float PAD_EDGE = 6f;         // intersection pad half size
    private static final float SLAB_TOP = 0.14f;          // buildings stand on the block interior
    private static final float ROAD_TOP = 0.05f;
    private static final float INTERIOR_TOP = 0.14f;      // block interior (grass, car park, plaza)
    private static final float SIDEWALK_TOP = 0.18f;      // raised pavement -> 13 cm kerb
    private static final float PAINT_Y = 0.066f;
    private static final float BLOCK_MIN = ROAD_HALF;
    private static final float BLOCK_MAX = P - ROAD_HALF;
    private static final int ZONE_CITY = 0;
    private static final int ZONE_PARK = 1;
    private static final int ZONE_LOT = 2;
    private static final int ZONE_PLAZA = 3;
    private static final int PED_COUNT = 30;
    private static final boolean ENABLE_SSAO = true;      // contact shadows in corners / under cars
    private static final boolean ENABLE_BLOOM = true;     // night glow around lights; set false if slow
    private static final float CELL_LOAD_RADIUS = 200f;
    private static final float CELL_KEEP_RADIUS = 250f;

    // ---------------------------------------------------------------- tuning
    private static final float CAR_SCALE = 1.6f;
    private static final int TRAFFIC_COUNT = 46;
    private static final float TRAFFIC_RECYCLE_DIST = 215f;
    private static final float GAUGE_MAX_KMH = 200f;
    private static final float MAP_HALF = 85f;          // minimap half size in pixels
    private static final float MAP_RANGE = 200f;        // metres shown from centre to edge
    private static final int GAUGE_SEGMENTS = 28;
    private static final float LIGHT_CYCLE = 22f;
    private static final float DAY_LENGTH = 360f;

    private static final int GREEN = 0;
    private static final int YELLOW = 1;
    private static final int RED = 2;

    private static final String[] TRAFFIC_MODELS = {
            "sedan", "sedan", "sedan-sports", "hatchback-sports", "suv", "suv", "suv-luxury",
            "taxi", "taxi", "van", "truck", "police", "ambulance", "firetruck", "garbage-truck"
    };
    private static final String[] PARKED_MODELS = {
            "sedan", "sedan", "sedan-sports", "hatchback-sports", "suv", "suv-luxury", "taxi", "van", "police"
    };
    private static final String[] EDGE_BUILDINGS = {
            "building-a", "building-b", "building-c", "building-d", "building-e", "building-f",
            "building-g", "building-h", "building-i", "building-j", "building-k", "building-l",
            "building-m", "building-n", "building-skyscraper-a", "building-skyscraper-b",
            "building-skyscraper-c", "building-skyscraper-d", "building-skyscraper-e"
    };
    private static final String[] CENTER_BUILDINGS = {
            "building-skyscraper-a", "building-skyscraper-b", "building-skyscraper-c",
            "building-skyscraper-d", "building-skyscraper-e", "building-skyscraper-a",
            "building-skyscraper-c", "building-n", "building-m", "building-k"
    };

    // ---------------------------------------------------------------- missions
    private static class Mission {
        final String name;
        final String description;
        final float timeLimit;       // 0 = unlimited
        final int goalCheckpoints;   // 0 = free roam
        final float checkpointBonus;
        final int maxCrashes;
        final int maxRedLights;
        final float startPhase;

        Mission(String name, String description, float timeLimit, int goalCheckpoints,
                float checkpointBonus, int maxCrashes, int maxRedLights, float startPhase) {
            this.name = name;
            this.description = description;
            this.timeLimit = timeLimit;
            this.goalCheckpoints = goalCheckpoints;
            this.checkpointBonus = checkpointBonus;
            this.maxCrashes = maxCrashes;
            this.maxRedLights = maxRedLights;
            this.startPhase = startPhase;
        }
    }

    private static final Mission[] MISSIONS = {
            new Mission("1. TAXI RUN",
                    "Reach 5 beacons in 120 s (+20 s each). Max 6 crashes. Daytime.",
                    120f, 5, 20f, 6, 99, 0.10f),
            new Mission("2. DUSK COURIER",
                    "Reach 7 beacons in 150 s (+18 s each). Max 4 crashes, 3 red lights.",
                    150f, 7, 18f, 4, 3, 0.42f),
            new Mission("3. NIGHT DELIVERY",
                    "Reach 10 beacons in 180 s (+16 s each) at night. Max 5 crashes, 3 red lights.",
                    180f, 10, 16f, 5, 3, 0.64f),
            new Mission("4. FREE ROAM",
                    "No timer, no goals. Just drive around the city.",
                    0f, 0, 0f, 9999, 9999, 0.20f)
    };

    private enum State { MENU, PLAYING, RESULT }

    // ---------------------------------------------------------------- selectable cars
    private static class CarSpec {
        final String name;
        final String model;
        final float maxSpeed;     // m/s  (km/h = x 2.2)
        final float accel;
        final float handling;     // steering strength multiplier
        final float nitroBonus;

        CarSpec(String name, String model, float maxSpeed, float accel, float handling, float nitroBonus) {
            this.name = name;
            this.model = model;
            this.maxSpeed = maxSpeed;
            this.accel = accel;
            this.handling = handling;
            this.nitroBonus = nitroBonus;
        }
    }

    private static final CarSpec[] CAR_SPECS = {
            new CarSpec("RACE CAR", "race", 66f, 26f, 1.00f, 24f),
            new CarSpec("FUTURE RACER", "race-future", 72f, 28f, 0.95f, 26f),
            new CarSpec("SPORTS SEDAN", "sedan-sports", 60f, 24f, 1.05f, 22f),
            new CarSpec("HOT HATCH", "hatchback-sports", 56f, 25f, 1.18f, 20f),
            new CarSpec("POLICE CAR", "police", 62f, 25f, 1.00f, 22f),
            new CarSpec("LUXURY SUV", "suv-luxury", 54f, 21f, 0.90f, 18f),
            new CarSpec("TAXI", "taxi", 48f, 20f, 1.00f, 16f),
            new CarSpec("DELIVERY VAN", "van", 42f, 16f, 0.85f, 14f)
    };

    // ---------------------------------------------------------------- data classes
    /** One generated city cell: intersection at its corner + road east + road south + one block. */
    private static class Cell {
        int i;
        int j;
        int zone;
        Node node;
        Material[] matsZ = new Material[3];   // signal lamps for traffic moving along Z
        Material[] matsX = new Material[3];   // signal lamps for traffic moving along X
        List<float[]> rects = new ArrayList<>();   // building footprints: minX, maxX, minZ, maxZ (world)
    }

    private static class Car {
        Node node;
        List<Spatial> wheels = new ArrayList<>();
        Node lights;
        int axis;            // 0 = moves along X, 1 = moves along Z
        int d;               // +1 / -1
        int k;               // road index (road is at z = k*P for axis 0, x = k*P for axis 1)
        float s;             // position along the axis
        boolean turning;
        float ex, ez, cx, cz, tx, tz, t, len;     // bezier for a right turn
        int newAxis, newD, newK;
        float newS;
        float speed;
        float base;
        float spin;
        float wx, wz, alpha;
        boolean close;
    }

    // ---------------------------------------------------------------- fields
    private final Random rnd = new Random();
    private State state = State.MENU;
    private int selectedMission = 0;
    private Mission mission = MISSIONS[0];
    private boolean missionComplete;
    private String resultReason = "";
    private final Properties scores = new Properties();

    // player
    private Node playerCar;
    private Node playerModelHolder;
    private int carIndex = 0;
    private float maxSpeed = 66f;
    private float nitroTop = 90f;
    private float accelRate = 26f;
    private float handling = 1f;
    private final List<Spatial> playerWheels = new ArrayList<>();
    private Material[] playerTail;
    private Node playerLights;
    private SpotLight headlight;
    private float px, pz;
    private float heading = FastMath.HALF_PI;   // model yaw: forward = (sin a, cos a)
    private float camHeading = FastMath.HALF_PI;
    private float speed;
    private float steering;
    private float wheelSpin;
    private float nitroEnergy = 1f;
    private boolean accelerating, braking, left, right, nitro;

    // mission stats
    private float timeLeft;
    private float odometer;
    private int checkpoints;
    private int nearMisses;
    private int crashes;
    private int redLights;
    private float crashCooldown;
    private float shake;
    private float messageTimer;
    private String message = "";
    private ColorRGBA messageColor = ColorRGBA.Red;
    private long finalScore;
    private int targetI;
    private int targetJ;
    private int lastPadI = Integer.MIN_VALUE;
    private int lastPadJ = Integer.MIN_VALUE;

    // world
    private float gameTime;
    private float dayPhase = 0.10f;
    private boolean lightsOn;
    private Geometry ground;
    private Node beacon;
    private Material beaconMat;
    private AmbientLight ambient;
    private DirectionalLight sun;
    private DirectionalLightShadowRenderer shadows;
    private FogFilter fog;
    private final Map<String, Material> matCache = new HashMap<>();
    private final Map<Long, Cell> cells = new HashMap<>();
    private final List<Car> cars = new ArrayList<>();

    // city look: materials shared by every cell
    private Material matAsphalt, matLot, matSidewalk, matGrass, matPavers, matWater;
    private Material matCurb, matWhite, matYellow, matPatchDark, matPatchLight, matManhole;
    private Material matMetal, matTrunk, matRed, matBin, matWood, matGlass, matBusSign, matSoil;
    private final Material[] matLeaf = new Material[3];
    private Material matStain;       // soft dark blobs: tyre wear, oil
    private Material matLamp;        // glowing lamp heads (brightness follows the time of day)
    private Material matPool;        // light pool sprite on the road
    private BloomFilter bloom;
    private ParticleEmitter smoke;
    private float playerDamage;     // 0..1, dents + smoke + lower top speed
    private int pedHits;
    private final List<Debris> debris = new ArrayList<>();
    private final List<Ped> peds = new ArrayList<>();

    // gui
    private BitmapText hud1;
    private BitmapText hud2;
    private BitmapText hudMission;
    private BitmapText hudHelp;
    private BitmapText banner;
    private BitmapText[] menuLines;
    private Node arrow;
    private BitmapText speedText;
    private Node mapRoot;
    private final List<Geometry> mapRoadQuads = new ArrayList<>();
    private final List<Geometry> mapCarDots = new ArrayList<>();
    private Geometry mapTarget;
    private Material mapTargetMat;
    private Node mapPlayer;
    private Geometry[] gaugeSegs;
    private Material[] gaugeMats;
    private Node gaugeNeedle;
    private int gaugeLit = -1;
    private Node gaugeRoot;

    // ================================================================= main
    public static void main(String[] args) {
        CarRacingGame app = new CarRacingGame();
        AppSettings settings = new AppSettings(true);
        settings.setTitle("Turbo Racer 3D");
        settings.setResolution(1280, 720);
        settings.setVSync(true);
        settings.setSamples(4);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        flyCam.setEnabled(false);
        setDisplayStatView(false);
        setDisplayFps(false);

        assetManager.registerLoader(GltfLoader.class, "gltf");
        assetManager.registerLoader(GlbLoader.class, "glb");

        cam.setFrustumPerspective(60f, (float) cam.getWidth() / cam.getHeight(), 0.5f, 420f);

        loadScores();
        addLighting();
        buildGround();
        initCityMaterials();
        buildPlayerCar();
        placePlayerAtStart();
        manageCells(10000);
        buildBeacon();
        buildTraffic();
        buildPeds();
        buildSmoke();
        buildHero();
        buildHud();
        setupInput();

        rootNode.setShadowMode(ShadowMode.CastAndReceive);
        ground.setShadowMode(ShadowMode.Receive);

        enterMenu();
    }

    // ================================================================= setup helpers
    private void addLighting() {
        ambient = new AmbientLight();
        ambient.setColor(ColorRGBA.White.mult(0.75f));
        rootNode.addLight(ambient);

        sun = new DirectionalLight();
        sun.setDirection(new Vector3f(-0.45f, -0.85f, -0.35f).normalizeLocal());
        sun.setColor(ColorRGBA.White.mult(1.25f));
        rootNode.addLight(sun);

        headlight = new SpotLight();
        headlight.setSpotRange(75f);
        headlight.setSpotInnerAngle(14f * FastMath.DEG_TO_RAD);
        headlight.setSpotOuterAngle(34f * FastMath.DEG_TO_RAD);
        headlight.setColor(ColorRGBA.Black);
        rootNode.addLight(headlight);

        shadows = new DirectionalLightShadowRenderer(assetManager, 2048, 3);
        shadows.setLight(sun);
        shadows.setShadowIntensity(0.45f);
        shadows.setShadowZExtend(150f);
        shadows.setEdgeFilteringMode(EdgeFilteringMode.PCF8);
        viewPort.addProcessor(shadows);

        FilterPostProcessor fpp = new FilterPostProcessor(assetManager);
        fpp.setNumSamples(4);
        if (ENABLE_SSAO) {
            fpp.addFilter(new SSAOFilter(2.9f, 32.92f, 5.8f, 0.091f));
        }
        fog = new FogFilter(new ColorRGBA(0.62f, 0.78f, 0.93f, 1f), 1.2f, 230f);
        fpp.addFilter(fog);
        if (ENABLE_BLOOM) {
            bloom = new BloomFilter(BloomFilter.GlowMode.Scene);
            bloom.setExposurePower(4f);
            bloom.setExposureCutOff(0.8f);
            bloom.setBloomIntensity(0f);
            fpp.addFilter(bloom);
        }
        viewPort.addProcessor(fpp);

        applyDayNight();
    }

    /** Shared lit material per colour (keeps the number of materials small). */
    private Material material(ColorRGBA color) {
        String key = color.r + "," + color.g + "," + color.b;
        Material m = matCache.get(key);
        if (m == null) {
            m = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
            m.setBoolean("UseMaterialColors", true);
            m.setColor("Diffuse", color);
            m.setColor("Ambient", color.mult(0.5f));
            matCache.put(key, m);
        }
        return m;
    }

    private Material unshaded(ColorRGBA color) {
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", color);
        return mat;
    }

    private Geometry box(String name, float hx, float hy, float hz, Vector3f pos, ColorRGBA color) {
        Geometry g = new Geometry(name, new Box(hx, hy, hz));
        g.setMaterial(material(color));
        g.setLocalTranslation(pos);
        return g;
    }

    private Geometry glowBox(String name, float hx, float hy, float hz, Vector3f pos, Material mat) {
        Geometry g = new Geometry(name, new Box(hx, hy, hz));
        g.setMaterial(mat);
        g.setLocalTranslation(pos);
        g.setShadowMode(ShadowMode.Off);
        return g;
    }

    private void buildGround() {
        ground = box("Ground", 700f, 0.1f, 700f, new Vector3f(0, -0.35f, 0),
                new ColorRGBA(0.30f, 0.34f, 0.31f, 1));
        rootNode.attachChild(ground);
    }

    private Spatial loadModel(String path) {
        try {
            return assetManager.loadModel(path);
        } catch (Exception e) {
            System.out.println("Could not load model: " + path);
            e.printStackTrace();
            return null;
        }
    }

    private Spatial loadCar(String name, List<Spatial> wheelsOut) {
        Spatial model = loadModel("Models/CarKit/" + name + ".glb");
        if (model == null) {
            return null;
        }
        model.setLocalScale(CAR_SCALE);
        glossify(model);
        if (model instanceof Node) {
            for (String w : new String[]{"wheel-front-left", "wheel-front-right",
                    "wheel-back-left", "wheel-back-right"}) {
                Spatial wheel = ((Node) model).getChild(w);
                if (wheel != null) {
                    wheelsOut.add(wheel);
                }
            }
        }
        return model;
    }

    /** Lowers roughness on PBR car materials so paint and glass catch the sun like real car paint. */
    private void glossify(Spatial s) {
        if (s instanceof Node) {
            for (Spatial c : ((Node) s).getChildren()) {
                glossify(c);
            }
        } else if (s instanceof Geometry) {
            Material m = ((Geometry) s).getMaterial();
            if (m != null && m.getMaterialDef() != null && m.getMaterialDef().getName().contains("PBR")) {
                try {
                    m.setFloat("Roughness", 0.42f);
                } catch (RuntimeException e) {
                    // material has no such parameter - leave it alone
                }
            }
        }
    }

    /** Head- and tail-light boxes for a car whose model faces +Z. */
    private Node makeCarLights(Material[] tailOut) {
        Node n = new Node("CarLights");
        Material head = unshaded(new ColorRGBA(1f, 0.96f, 0.8f, 1f));
        for (int i = 0; i < 2; i++) {
            float x = i == 0 ? -0.85f : 0.85f;
            n.attachChild(glowBox("Head", 0.2f, 0.1f, 0.06f, new Vector3f(x, 0.7f, 2.05f), head));
            Material tail = unshaded(new ColorRGBA(0.75f, 0.03f, 0.03f, 1f));
            if (tailOut != null && i < tailOut.length) {
                tailOut[i] = tail;
            }
            n.attachChild(glowBox("Tail", 0.2f, 0.1f, 0.06f, new Vector3f(x, 0.7f, -2.05f), tail));
        }
        n.setCullHint(lightsOn ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
        return n;
    }

    private void applyCarSpec() {
        CarSpec spec = CAR_SPECS[carIndex];
        maxSpeed = spec.maxSpeed;
        nitroTop = spec.maxSpeed + spec.nitroBonus;
        accelRate = spec.accel;
        handling = spec.handling;
    }

    /** Swaps the 3D model of the player's car. */
    private void setPlayerCarModel(int index) {
        carIndex = (index + CAR_SPECS.length) % CAR_SPECS.length;
        applyCarSpec();
        playerModelHolder.detachAllChildren();
        playerWheels.clear();
        Spatial model = loadCar(CAR_SPECS[carIndex].model, playerWheels);
        if (model != null) {
            playerModelHolder.attachChild(model);
        } else {
            playerModelHolder.attachChild(box("FallbackCar", 1.0f, 0.5f, 2.0f,
                    new Vector3f(0, 0.6f, 0), new ColorRGBA(0.9f, 0.08f, 0.04f, 1)));
        }
    }

    private void buildPlayerCar() {
        playerCar = new Node("PlayerCar");
        playerModelHolder = new Node("PlayerModel");
        playerCar.attachChild(playerModelHolder);
        try {
            carIndex = Integer.parseInt(scores.getProperty("car", "0"));
        } catch (NumberFormatException e) {
            carIndex = 0;
        }
        setPlayerCarModel(carIndex);
        playerTail = new Material[2];
        playerLights = makeCarLights(playerTail);
        playerCar.attachChild(playerLights);
        rootNode.attachChild(playerCar);
    }

    private void placePlayerAtStart() {
        px = P / 2f;
        pz = LANE;                       // lane for traffic moving +X on road z = 0
        heading = FastMath.HALF_PI;      // facing +X
        camHeading = heading;
        speed = 0f;
        steering = 0f;
        syncPlayerNode();
        Vector3f cp = new Vector3f(px - 12f, 4.5f, pz);
        cam.setLocation(cp);
    }

    private void syncPlayerNode() {
        float x = onFoot ? carX : px;
        float z = onFoot ? carZ : pz;
        float h = onFoot ? carHeading : heading;
        playerCar.setLocalTranslation(x, 0, z);
        playerCar.setLocalRotation(new Quaternion().fromAngleAxis(h, Vector3f.UNIT_Y));
    }

    // ================================================================= city cells
    private long cellKey(int i, int j) {
        return (((long) i) << 32) ^ (j & 0xffffffffL);
    }

    private void manageCells(int maxCreate) {
        int ci = (int) Math.floor(px / P);
        int cj = (int) Math.floor(pz / P);
        int span = (int) Math.ceil(CELL_LOAD_RADIUS / P) + 1;
        int created = 0;
        for (int i = ci - span; i <= ci + span + 1 && created < maxCreate; i++) {
            for (int j = cj - span; j <= cj + span + 1 && created < maxCreate; j++) {
                long key = cellKey(i, j);
                if (cells.containsKey(key)) {
                    continue;
                }
                float cx = (i + 0.5f) * P;
                float cz = (j + 0.5f) * P;
                float dx = cx - px;
                float dz = cz - pz;
                if (dx * dx + dz * dz <= CELL_LOAD_RADIUS * CELL_LOAD_RADIUS) {
                    cells.put(key, buildCell(i, j));
                    created++;
                }
            }
        }
        List<Long> remove = new ArrayList<>();
        for (Map.Entry<Long, Cell> e : cells.entrySet()) {
            Cell c = e.getValue();
            float dx = (c.i + 0.5f) * P - px;
            float dz = (c.j + 0.5f) * P - pz;
            if (dx * dx + dz * dz > CELL_KEEP_RADIUS * CELL_KEEP_RADIUS) {
                remove.add(e.getKey());
            }
        }
        for (Long key : remove) {
            Cell c = cells.remove(key);
            c.node.removeFromParent();
        }
    }

    private Cell buildCell(int i, int j) {
        Cell cell = new Cell();
        cell.i = i;
        cell.j = j;
        cell.node = new Node("Cell");
        float ox = i * P;
        float oz = j * P;
        cell.node.setLocalTranslation(ox, 0, oz);

        Random r = new Random((i * 73856093L) ^ (j * 19349663L) ^ 0x9E3779B9L);
        int zone = ZONE_CITY;
        float zr = r.nextFloat();
        if (i != 0 || j != 0) {                 // the start block is always a normal city block
            if (zr < 0.13f) {
                zone = ZONE_PARK;
            } else if (zr < 0.22f) {
                zone = ZONE_LOT;
            } else if (zr < 0.30f) {
                zone = ZONE_PLAZA;
            }
        }
        cell.zone = zone;

        MeshBatch ground = new MeshBatch();     // roads, pavement, paint  (receives shadows)
        MeshBatch props = new MeshBatch();      // lamps, trees, benches   (casts + receives)
        MeshBatch glow = new MeshBatch();       // lamp heads              (unshaded)
        MeshBatch pools = new MeshBatch();      // light pools on the road (transparent)

        buildRoadSurface(ground, pools, ox, oz, r);
        buildBlock(cell, ground, props, glow, pools, ox, oz, r);

        // traffic signals: pole stands on the driver's right, facing the approaching car
        cell.matsZ = newLampMaterials();
        cell.matsX = newLampMaterials();
        addSignal(cell.node, cell.matsZ, -6.8f, -7.5f, FastMath.PI);          // car moving +Z
        addSignal(cell.node, cell.matsZ, 6.8f, 7.5f, 0f);                     // car moving -Z
        addSignal(cell.node, cell.matsX, -7.5f, 6.8f, -FastMath.HALF_PI);     // car moving +X
        addSignal(cell.node, cell.matsX, 7.5f, -6.8f, FastMath.HALF_PI);      // car moving -X

        // buildings, deterministic per cell
        if (zone != ZONE_PARK) {
            for (int edge = 0; edge < 4; edge++) {
                if (edge < 2) {
                    addEdgeBuilding(cell, r, edge, 17f);
                    addEdgeBuilding(cell, r, edge, 51f);
                } else {
                    addEdgeBuilding(cell, r, edge, 34f);
                }
            }
        }
        if (zone == ZONE_CITY) {
            addCenterBuilding(cell, r);
        }
        if (zone == ZONE_LOT) {
            addParkingLot(cell, ground, props, glow, pools, ox, oz, r);
        }

        ground.build(cell.node, ShadowMode.Receive, false);
        props.build(cell.node, ShadowMode.CastAndReceive, false);
        glow.build(cell.node, ShadowMode.Off, false);
        pools.build(cell.node, ShadowMode.Off, true);

        rootNode.attachChild(cell.node);
        return cell;
    }

    // ----------------------------------------------------------------- road surface + paint
    private void paint(MeshBatch g, Material m, float x0, float x1, float z0, float z1) {
        g.flat(m, x0, x1, z0, z1, PAINT_Y, 0f, 0f, 0f);
    }

    /** Asphalt for the intersection + the road east and the road south of it, with all road paint. */
    private void buildRoadSurface(MeshBatch g, MeshBatch t, float ox, float oz, Random r) {
        g.flat(matAsphalt, -PAD_EDGE, PAD_EDGE, -ROAD_HALF, ROAD_HALF, ROAD_TOP, 8f, ox, oz);
        g.flat(matAsphalt, PAD_EDGE, P - PAD_EDGE, -ROAD_HALF, ROAD_HALF, ROAD_TOP, 8f, ox, oz);
        g.flat(matAsphalt, -ROAD_HALF, ROAD_HALF, PAD_EDGE, P - PAD_EDGE, ROAD_TOP, 8f, ox, oz);

        // repaired patches and manholes (east road: along X, south road: along Z)
        for (int axis = 0; axis < 2; axis++) {
            for (int k = 0; k < 5; k++) {
                float len = 1.2f + r.nextFloat() * 3.5f;
                float wid = 0.8f + r.nextFloat() * 1.8f;
                float a = 14f + r.nextFloat() * 52f;
                float b = (r.nextBoolean() ? LANE : -LANE) + (r.nextFloat() - 0.5f) * 2.4f;
                Material m = r.nextBoolean() ? matPatchDark : matPatchLight;
                if (axis == 0) {
                    g.flat(m, a - len, a + len, b - wid, b + wid, ROAD_TOP + 0.008f, 0f, 0f, 0f);
                } else {
                    g.flat(m, b - wid, b + wid, a - len, a + len, ROAD_TOP + 0.008f, 0f, 0f, 0f);
                }
            }
            for (int k = 0; k < 2; k++) {
                float a = 18f + r.nextFloat() * 44f;
                float b = (r.nextBoolean() ? 2.2f : -2.2f) + (r.nextFloat() - 0.5f) * 0.6f;
                if (axis == 0) {
                    g.cylinder(matManhole, a, ROAD_TOP, b, 0.45f, 0.012f, 10, 0f, 0f, 0f);
                } else {
                    g.cylinder(matManhole, b, ROAD_TOP, a, 0.45f, 0.012f, 10, 0f, 0f, 0f);
                }
            }
        }

        // worn wheel paths in every lane, heavier near the stop lines, plus oil stains
        float ty = ROAD_TOP + 0.004f;
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            for (float w : new float[]{-0.85f, 0.85f}) {
                float c = sgn * LANE + w;
                for (float a = 9f; a < 72f; a += 3.2f) {
                    float near = (a < 22f || a > 58f) ? 1.0f : 0.7f;
                    if (r.nextFloat() > near) {
                        continue;
                    }
                    float hl = 2.0f + r.nextFloat() * 0.6f;
                    float hw = 0.42f + r.nextFloat() * 0.2f;
                    t.flat(matStain, a - hl, a + hl, c - hw, c + hw, ty, 0f, 0f, 0f);
                    t.flat(matStain, c - hw, c + hw, a - hl, a + hl, ty, 0f, 0f, 0f);
                }
            }
        }
        for (int k = 0; k < 4; k++) {
            float a = 12f + r.nextFloat() * 56f;
            float b = (r.nextBoolean() ? LANE : -LANE) + (r.nextFloat() - 0.5f) * 1.6f;
            float rad = 0.6f + r.nextFloat() * 0.9f;
            t.flat(matStain, a - rad, a + rad, b - rad, b + rad, ty + 0.001f, 0f, 0f, 0f);
        }

        // lane arrows 12 m before each stop line
        arrow(g, 60f, LANE, 1f, 0f);
        arrow(g, 20f, -LANE, -1f, 0f);
        arrow(g, -LANE, 60f, 0f, 1f);
        arrow(g, LANE, 20f, 0f, -1f);

        // double yellow centre lines and white edge lines
        for (float off : new float[]{-0.18f, 0.18f}) {
            paint(g, matYellow, PAD_EDGE, P - PAD_EDGE, off - 0.07f, off + 0.07f);
            paint(g, matYellow, off - 0.07f, off + 0.07f, PAD_EDGE, P - PAD_EDGE);
        }
        for (float e : new float[]{-5.5f, 5.35f}) {
            paint(g, matWhite, PAD_EDGE, P - PAD_EDGE, e, e + 0.15f);
            paint(g, matWhite, e, e + 0.15f, PAD_EDGE, P - PAD_EDGE);
        }

        // zebra crossings: 8 wide stripes on each of the 4 approaches
        for (int k = 0; k < 4; k++) {
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                float zc = sgn * (0.7f + 1.2f * k);
                paint(g, matWhite, 6.5f, 8.3f, zc - 0.35f, zc + 0.35f);       // east arm
                paint(g, matWhite, -8.3f, -6.5f, zc - 0.35f, zc + 0.35f);     // west arm
                paint(g, matWhite, zc - 0.35f, zc + 0.35f, 6.5f, 8.3f);       // south arm
                paint(g, matWhite, zc - 0.35f, zc + 0.35f, -8.3f, -6.5f);     // north arm
            }
        }
        // stop lines (one lane wide)
        paint(g, matWhite, -STOP_DIST - 0.2f, -STOP_DIST + 0.2f, 0.4f, 5.2f);
        paint(g, matWhite, STOP_DIST - 0.2f, STOP_DIST + 0.2f, -5.2f, -0.4f);
        paint(g, matWhite, -5.2f, -0.4f, -STOP_DIST - 0.2f, -STOP_DIST + 0.2f);
        paint(g, matWhite, 0.4f, 5.2f, STOP_DIST - 0.2f, STOP_DIST + 0.2f);
    }

    /** Straight-ahead road arrow whose tail is at (cx,cz) and which points along (dx,dz) (an axis). */
    private void arrow(MeshBatch g, float cx, float cz, float dx, float dz) {
        float[][] parts = {{0f, 2.6f, 0.14f}, {2.6f, 2.9f, 0.55f}, {2.9f, 3.2f, 0.40f},
                {3.2f, 3.5f, 0.25f}, {3.5f, 3.8f, 0.10f}};
        for (float[] pt : parts) {
            float a0 = pt[0];
            float a1 = pt[1];
            float w = pt[2];
            if (dx != 0f) {
                float xa = cx + dx * a0;
                float xb = cx + dx * a1;
                paint(g, matWhite, Math.min(xa, xb), Math.max(xa, xb), cz - w, cz + w);
            } else {
                float za = cz + dz * a0;
                float zb = cz + dz * a1;
                paint(g, matWhite, cx - w, cx + w, Math.min(za, zb), Math.max(za, zb));
            }
        }
    }

    // ----------------------------------------------------------------- city block
    private void sidewalk(MeshBatch g, float x0, float x1, float z0, float z1, float ox, float oz) {
        float hy = (SIDEWALK_TOP - 0.01f) / 2f;
        g.box(matCurb, (x0 + x1) / 2f, hy, (z0 + z1) / 2f, (x1 - x0) / 2f, hy, (z1 - z0) / 2f);
        g.flat(matSidewalk, x0, x1, z0, z1, SIDEWALK_TOP, 8f, ox, oz);
    }

    private void addRect(Cell cell, float lx, float lz, float hx, float hz) {
        float wx = cell.i * P + lx;
        float wz = cell.j * P + lz;
        cell.rects.add(new float[]{wx - hx, wx + hx, wz - hz, wz + hz});
    }

    private boolean overlapsRects(Cell cell, float lx, float lz, float hx, float hz) {
        float wx = cell.i * P + lx;
        float wz = cell.j * P + lz;
        for (float[] q : cell.rects) {
            if (wx + hx > q[0] && wx - hx < q[1] && wz + hz > q[2] && wz - hz < q[3]) {
                return true;
            }
        }
        return false;
    }

    // block-edge coordinates: edge 0 = north (small Z), 1 = south, 2 = west (small X), 3 = east.
    // t runs along the edge (0..68), d is the distance inwards from the kerb.
    private float edgeX(int edge, float t, float d) {
        switch (edge) {
            case 0:
            case 1: return BLOCK_MIN + t;
            case 2: return BLOCK_MIN + d;
            default: return BLOCK_MAX - d;
        }
    }

    private float edgeZ(int edge, float t, float d) {
        switch (edge) {
            case 0: return BLOCK_MIN + d;
            case 1: return BLOCK_MAX - d;
            default: return BLOCK_MIN + t;
        }
    }

    /** Unit vector from the pavement towards the road. */
    private float roadDirX(int edge) {
        return edge == 2 ? -1f : (edge == 3 ? 1f : 0f);
    }

    private float roadDirZ(int edge) {
        return edge == 0 ? -1f : (edge == 1 ? 1f : 0f);
    }

    /** Box placed relative to a block edge: hAlong runs along the edge, hAcross across it. */
    private void edgeBox(MeshBatch b, Material m, int edge, float t, float d, float y,
                         float hAlong, float hy, float hAcross) {
        boolean alongX = edge < 2;
        b.box(m, edgeX(edge, t, d), y, edgeZ(edge, t, d),
                alongX ? hAlong : hAcross, hy, alongX ? hAcross : hAlong);
    }

    private void buildBlock(Cell cell, MeshBatch g, MeshBatch props, MeshBatch glow, MeshBatch pools,
                            float ox, float oz, Random r) {
        int zone = cell.zone;
        Material interior;
        float tile;
        switch (zone) {
            case ZONE_PARK: interior = matGrass; tile = 10f; break;
            case ZONE_PLAZA: interior = matPavers; tile = 4f; break;
            default: interior = matLot; tile = 8f; break;
        }
        g.flat(interior, BLOCK_MIN, BLOCK_MAX, BLOCK_MIN, BLOCK_MAX, INTERIOR_TOP, tile, ox, oz);

        // raised pavement ring around the block, with a pale kerb stone along the road side
        float sw = 3f;
        sidewalk(g, BLOCK_MIN, BLOCK_MAX, BLOCK_MIN, BLOCK_MIN + sw, ox, oz);
        sidewalk(g, BLOCK_MIN, BLOCK_MAX, BLOCK_MAX - sw, BLOCK_MAX, ox, oz);
        sidewalk(g, BLOCK_MIN, BLOCK_MIN + sw, BLOCK_MIN + sw, BLOCK_MAX - sw, ox, oz);
        sidewalk(g, BLOCK_MAX - sw, BLOCK_MAX, BLOCK_MIN + sw, BLOCK_MAX - sw, ox, oz);
        float ky = SIDEWALK_TOP + 0.012f;
        g.flat(matCurb, BLOCK_MIN, BLOCK_MAX, BLOCK_MIN, BLOCK_MIN + 0.25f, ky, 0f, 0f, 0f);
        g.flat(matCurb, BLOCK_MIN, BLOCK_MAX, BLOCK_MAX - 0.25f, BLOCK_MAX, ky, 0f, 0f, 0f);
        g.flat(matCurb, BLOCK_MIN, BLOCK_MIN + 0.25f, BLOCK_MIN, BLOCK_MAX, ky, 0f, 0f, 0f);
        g.flat(matCurb, BLOCK_MAX - 0.25f, BLOCK_MAX, BLOCK_MIN, BLOCK_MAX, ky, 0f, 0f, 0f);

        for (int edge = 0; edge < 4; edge++) {
            buildEdgeProps(cell, props, glow, pools, r, edge);
        }

        if (zone == ZONE_PARK) {
            buildPark(cell, g, props, glow, pools, ox, oz, r);
        } else if (zone == ZONE_PLAZA) {
            buildPlaza(cell, g, props, glow, pools, r);
        }
    }

    // ----------------------------------------------------------------- street furniture
    private void buildEdgeProps(Cell cell, MeshBatch props, MeshBatch glow, MeshBatch pools,
                                Random r, int edge) {
        streetLamp(cell, props, glow, pools, edge, 22f + r.nextFloat() * 2f);
        streetLamp(cell, props, glow, pools, edge, 52f + r.nextFloat() * 2f);
        float[] treeT = {13f, 32f, 42f, 62f};
        for (float t : treeT) {
            if (r.nextFloat() < 0.8f) {
                tree(cell, props, edgeX(edge, t, 1.7f), edgeZ(edge, t, 1.7f), SIDEWALK_TOP,
                        0.85f + 0.35f * r.nextFloat(), r);
            }
        }
        float roll = r.nextFloat();
        if (roll < 0.35f) {
            bench(props, edge, 37f, 1.4f, SIDEWALK_TOP);
        } else if (roll < 0.50f) {
            busStop(props, edge, 37f);
        }
        if (r.nextFloat() < 0.6f) {
            hydrant(props, edgeX(edge, 27f, 0.6f), edgeZ(edge, 27f, 0.6f), SIDEWALK_TOP);
        }
        if (r.nextFloat() < 0.6f) {
            bin(props, edgeX(edge, 47f, 0.6f), edgeZ(edge, 47f, 0.6f), SIDEWALK_TOP);
        }
    }

    /** Curved-arm street light that reaches over the road; glows and lights the road at night. */
    private void streetLamp(Cell cell, MeshBatch props, MeshBatch glow, MeshBatch pools, int edge, float t) {
        float x = edgeX(edge, t, 0.7f);
        float z = edgeZ(edge, t, 0.7f);
        float rx = roadDirX(edge);
        float rz = roadDirZ(edge);
        props.cylinder(matMetal, x, SIDEWALK_TOP, z, 0.09f, 7f, 8, 0f, 0f, 0f);
        float y = SIDEWALK_TOP + 7f;
        props.box(matMetal, x + rx * 0.85f, y, z + rz * 0.85f,
                rx != 0f ? 0.85f : 0.05f, 0.05f, rz != 0f ? 0.85f : 0.05f);
        float hx = x + rx * 1.7f;
        float hz = z + rz * 1.7f;
        props.box(matMetal, hx, y + 0.02f, hz, rx != 0f ? 0.34f : 0.19f, 0.04f, rz != 0f ? 0.34f : 0.19f);
        glow.box(matLamp, hx, y - 0.05f, hz, rx != 0f ? 0.3f : 0.15f, 0.03f, rz != 0f ? 0.3f : 0.15f);
        pools.flat(matPool, hx - 7f, hx + 7f, hz - 7f, hz + 7f, 0.075f, 0f, 0f, 0f);
        addRect(cell, x, z, 0.2f, 0.2f);
    }

    /** Short garden / car park light on a pole. */
    private void areaLamp(Cell cell, MeshBatch props, MeshBatch glow, MeshBatch pools,
                          float x, float z, float baseY) {
        props.cylinder(matMetal, x, baseY, z, 0.07f, 4.6f, 8, 0f, 0f, 0f);
        props.box(matMetal, x, baseY + 4.62f, z, 0.32f, 0.04f, 0.32f);
        glow.box(matLamp, x, baseY + 4.55f, z, 0.27f, 0.03f, 0.27f);
        pools.flat(matPool, x - 8f, x + 8f, z - 8f, z + 8f, baseY + 0.07f, 0f, 0f, 0f);
        addRect(cell, x, z, 0.18f, 0.18f);
    }

    private void tree(Cell cell, MeshBatch props, float x, float z, float baseY, float s, Random r) {
        props.cylinder(matTrunk, x, baseY, z, 0.16f * s, 2.4f * s, 6, 0f, 0f, 0f);
        Material leaf = matLeaf[r.nextInt(matLeaf.length)];
        Material leaf2 = matLeaf[r.nextInt(matLeaf.length)];
        float cy = baseY + 3.0f * s;
        props.ellipsoid(leaf, x, cy, z, 1.7f * s, 1.5f * s, 1.7f * s, 5, 8);
        props.ellipsoid(leaf2, x + 0.6f * s, cy + 0.9f * s, z - 0.3f * s, 1.2f * s, 1.1f * s, 1.2f * s, 4, 7);
        props.ellipsoid(leaf, x - 0.6f * s, cy + 0.4f * s, z + 0.5f * s, 1.2f * s, 1.1f * s, 1.2f * s, 4, 7);
        addRect(cell, x, z, 0.3f, 0.3f);
    }

    private void hydrant(MeshBatch props, float x, float z, float baseY) {
        props.cylinder(matRed, x, baseY, z, 0.12f, 0.6f, 8, 0f, 0f, 0f);
        props.ellipsoid(matRed, x, baseY + 0.62f, z, 0.15f, 0.1f, 0.15f, 3, 8);
        props.box(matRed, x, baseY + 0.4f, z, 0.2f, 0.04f, 0.04f);
    }

    private void bin(MeshBatch props, float x, float z, float baseY) {
        props.cylinder(matBin, x, baseY, z, 0.3f, 0.85f, 8, 0f, 0f, 0f);
        props.cylinder(matMetal, x, baseY + 0.85f, z, 0.32f, 0.06f, 8, 0f, 0f, 0f);
    }

    /** Park bench facing the road (d grows away from the kerb, so the back rest sits at larger d). */
    private void bench(MeshBatch props, int edge, float t, float d, float y) {
        edgeBox(props, matWood, edge, t, d, y + 0.45f, 0.8f, 0.04f, 0.22f);
        edgeBox(props, matWood, edge, t, d + 0.2f, y + 0.75f, 0.8f, 0.2f, 0.025f);
        edgeBox(props, matMetal, edge, t - 0.65f, d, y + 0.22f, 0.04f, 0.22f, 0.2f);
        edgeBox(props, matMetal, edge, t + 0.65f, d, y + 0.22f, 0.04f, 0.22f, 0.2f);
    }

    private void busStop(MeshBatch props, int edge, float t) {
        float y = SIDEWALK_TOP;
        edgeBox(props, matGlass, edge, t, 1.9f, y + 1.2f, 1.4f, 1.1f, 0.03f);          // back panel
        edgeBox(props, matMetal, edge, t, 1.2f, y + 2.35f, 1.55f, 0.05f, 0.85f);       // roof
        for (int s = -1; s <= 1; s += 2) {
            edgeBox(props, matMetal, edge, t + s * 1.4f, 0.5f, y + 1.15f, 0.04f, 1.15f, 0.04f);
            edgeBox(props, matMetal, edge, t + s * 1.4f, 1.85f, y + 1.15f, 0.04f, 1.15f, 0.04f);
        }
        bench(props, edge, t, 1.5f, y);
        edgeBox(props, matBusSign, edge, t + 2.2f, 0.5f, y + 2.3f, 0.03f, 0.4f, 0.25f);   // bus sign
        edgeBox(props, matMetal, edge, t + 2.2f, 0.5f, y + 1.0f, 0.03f, 1.0f, 0.03f);
    }

    // ----------------------------------------------------------------- park / plaza / car park
    private void fountain(MeshBatch props, float x, float z, float y) {
        props.cylinder(matCurb, x, y, z, 3.0f, 0.45f, 20, 0f, 0f, 0f);
        props.cylinder(matWater, x, y + 0.44f, z, 2.75f, 0.02f, 20, 0f, 0f, 0f);
        props.cylinder(matCurb, x, y + 0.45f, z, 0.3f, 1.1f, 10, 0f, 0f, 0f);
        props.cylinder(matCurb, x, y + 1.5f, z, 1.0f, 0.15f, 14, 0f, 0f, 0f);
        props.cylinder(matWater, x, y + 1.65f, z, 0.9f, 0.02f, 14, 0f, 0f, 0f);
        props.cylinder(matWater, x, y + 1.65f, z, 0.06f, 1.1f, 6, 0f, 0f, 0f);
    }

    private void buildPark(Cell cell, MeshBatch g, MeshBatch props, MeshBatch glow, MeshBatch pools,
                           float ox, float oz, Random r) {
        float c = P / 2f;
        g.flat(matPavers, c - 1.5f, c + 1.5f, 9f, P - 9f, 0.16f, 4f, ox, oz);
        g.flat(matPavers, 9f, P - 9f, c - 1.5f, c + 1.5f, 0.16f, 4f, ox, oz);
        g.cylinder(matPavers, c, INTERIOR_TOP, c, 7f, 0.03f, 20, 4f, ox, oz);
        fountain(props, c, c, INTERIOR_TOP + 0.03f);
        addRect(cell, c, c, 3.1f, 3.1f);

        areaLamp(cell, props, glow, pools, c - 6f, c - 14f, INTERIOR_TOP);
        areaLamp(cell, props, glow, pools, c + 6f, c + 14f, INTERIOR_TOP);
        areaLamp(cell, props, glow, pools, c - 14f, c + 6f, INTERIOR_TOP);
        areaLamp(cell, props, glow, pools, c + 14f, c - 6f, INTERIOR_TOP);

        // benches next to the paths
        props.box(matWood, c - 2.6f, INTERIOR_TOP + 0.45f, 24f, 0.22f, 0.04f, 0.8f);
        props.box(matWood, c - 2.8f, INTERIOR_TOP + 0.75f, 24f, 0.025f, 0.2f, 0.8f);
        props.box(matWood, c + 2.6f, INTERIOR_TOP + 0.45f, 56f, 0.22f, 0.04f, 0.8f);
        props.box(matWood, c + 2.8f, INTERIOR_TOP + 0.75f, 56f, 0.025f, 0.2f, 0.8f);
        props.box(matWood, 24f, INTERIOR_TOP + 0.45f, c + 2.6f, 0.8f, 0.04f, 0.22f);
        props.box(matWood, 24f, INTERIOR_TOP + 0.75f, c + 2.8f, 0.8f, 0.2f, 0.025f);
        props.box(matWood, 56f, INTERIOR_TOP + 0.45f, c - 2.6f, 0.8f, 0.04f, 0.22f);
        props.box(matWood, 56f, INTERIOR_TOP + 0.75f, c - 2.8f, 0.8f, 0.2f, 0.025f);

        // scattered trees (kept off the paths, the fountain plaza and each other)
        List<float[]> placed = new ArrayList<>();
        for (int n = 0; n < 90 && placed.size() < 26; n++) {
            float x = 11f + r.nextFloat() * 58f;
            float z = 11f + r.nextFloat() * 58f;
            if (Math.abs(x - c) < 3.6f || Math.abs(z - c) < 3.6f) {
                continue;
            }
            if (Math.hypot(x - c, z - c) < 9.5f) {
                continue;
            }
            boolean tooClose = false;
            for (float[] q : placed) {
                if (Math.hypot(x - q[0], z - q[1]) < 5.5f) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) {
                continue;
            }
            placed.add(new float[]{x, z});
            tree(cell, props, x, z, INTERIOR_TOP, 0.9f + 0.5f * r.nextFloat(), r);
        }
    }

    private void buildPlaza(Cell cell, MeshBatch g, MeshBatch props, MeshBatch glow, MeshBatch pools,
                            Random r) {
        float c = P / 2f;
        fountain(props, c, c, INTERIOR_TOP);
        addRect(cell, c, c, 3.1f, 3.1f);
        float[][] planters = {{c - 12f, c - 12f}, {c + 12f, c - 12f}, {c - 12f, c + 12f}, {c + 12f, c + 12f}};
        for (float[] pl : planters) {
            if (overlapsRects(cell, pl[0], pl[1], 2f, 2f)) {
                continue;
            }
            props.box(matCurb, pl[0], INTERIOR_TOP + 0.2f, pl[1], 1.6f, 0.2f, 1.6f);
            g.flat(matSoil, pl[0] - 1.45f, pl[0] + 1.45f, pl[1] - 1.45f, pl[1] + 1.45f,
                    INTERIOR_TOP + 0.405f, 0f, 0f, 0f);
            tree(cell, props, pl[0], pl[1], INTERIOR_TOP + 0.4f, 0.9f + 0.3f * r.nextFloat(), r);
        }
        areaLamp(cell, props, glow, pools, c - 8f, c, INTERIOR_TOP);
        areaLamp(cell, props, glow, pools, c + 8f, c, INTERIOR_TOP);
        props.box(matWood, c, INTERIOR_TOP + 0.45f, c - 5.4f, 0.8f, 0.04f, 0.22f);
        props.box(matWood, c, INTERIOR_TOP + 0.75f, c - 5.6f, 0.8f, 0.2f, 0.025f);
        props.box(matWood, c, INTERIOR_TOP + 0.45f, c + 5.4f, 0.8f, 0.04f, 0.22f);
        props.box(matWood, c, INTERIOR_TOP + 0.75f, c + 5.6f, 0.8f, 0.2f, 0.025f);
    }

    /** Painted bays with a few parked cars. */
    private void addParkingLot(Cell cell, MeshBatch g, MeshBatch props, MeshBatch glow, MeshBatch pools,
                               float ox, float oz, Random r) {
        float y = INTERIOR_TOP + 0.012f;
        float x0 = 24f;
        float x1 = 56f;
        int bays = 12;
        float bw = (x1 - x0) / bays;
        float[][] rows = {{23.5f, 29f}, {35f, 40.5f}, {40.5f, 46f}, {51.5f, 57f}};
        for (float[] row : rows) {
            for (int k = 0; k <= bays; k++) {
                float x = x0 + k * bw;
                g.flat(matWhite, x - 0.05f, x + 0.05f, row[0], row[1], y, 0f, 0f, 0f);
            }
            g.flat(matWhite, x0, x1, row[0] - 0.05f, row[0] + 0.05f, y, 0f, 0f, 0f);
            g.flat(matWhite, x0, x1, row[1] - 0.05f, row[1] + 0.05f, y, 0f, 0f, 0f);
        }
        areaLamp(cell, props, glow, pools, 40f, 32f, INTERIOR_TOP);
        areaLamp(cell, props, glow, pools, 40f, 48.7f, INTERIOR_TOP);

        List<Spatial> unusedWheels = new ArrayList<>();
        for (int row = 0; row < rows.length; row++) {
            float zc = (rows[row][0] + rows[row][1]) / 2f;
            for (int k = 0; k < bays; k++) {
                if (r.nextFloat() > 0.38f) {
                    continue;
                }
                float xc = x0 + (k + 0.5f) * bw;
                if (overlapsRects(cell, xc, zc, 1.0f, 2.1f)) {
                    continue;
                }
                String model = PARKED_MODELS[r.nextInt(PARKED_MODELS.length)];
                unusedWheels.clear();
                Spatial car = loadCar(model, unusedWheels);
                if (car == null) {
                    continue;
                }
                Node holder = new Node("Parked");
                holder.attachChild(car);
                holder.setLocalTranslation(xc, INTERIOR_TOP, zc);
                float yaw = (row < 2 ? 0f : FastMath.PI) + (r.nextFloat() - 0.5f) * 0.06f;
                holder.setLocalRotation(new Quaternion().fromAngleAxis(yaw, Vector3f.UNIT_Y));
                cell.node.attachChild(holder);
                addRect(cell, xc, zc, 0.95f, 2.0f);
            }
        }
    }

    private Material[] newLampMaterials() {
        return new Material[]{
                unshaded(new ColorRGBA(0.22f, 0f, 0f, 1f)),
                unshaded(new ColorRGBA(0.22f, 0.17f, 0f, 1f)),
                unshaded(new ColorRGBA(0f, 0.18f, 0f, 1f))
        };
    }

    private void addSignal(Node parent, Material[] mats, float x, float z, float yaw) {
        Node n = new Node("Signal");
        n.setLocalTranslation(x, 0, z);
        n.setLocalRotation(new Quaternion().fromAngleAxis(yaw, Vector3f.UNIT_Y));
        n.attachChild(box("Pole", 0.12f, 2.5f, 0.12f, new Vector3f(0, 2.5f, -0.05f),
                new ColorRGBA(0.2f, 0.2f, 0.22f, 1)));
        n.attachChild(box("Head", 0.4f, 1.0f, 0.28f, new Vector3f(0, 5.0f, 0),
                new ColorRGBA(0.05f, 0.05f, 0.06f, 1)));
        n.attachChild(glowBox("Red", 0.22f, 0.22f, 0.04f, new Vector3f(0, 5.6f, 0.30f), mats[0]));
        n.attachChild(glowBox("Yellow", 0.22f, 0.22f, 0.04f, new Vector3f(0, 5.0f, 0.30f), mats[1]));
        n.attachChild(glowBox("Green", 0.22f, 0.22f, 0.04f, new Vector3f(0, 4.4f, 0.30f), mats[2]));
        parent.attachChild(n);
    }

    /**
     * edge: 0 = north side of the block (small Z), 1 = south, 2 = west (small X), 3 = east.
     * t = position of the building centre along the block edge (0..68).
     */
    private void addEdgeBuilding(Cell cell, Random r, int edge, float t) {
        if (r.nextFloat() < 0.10f) {
            return;                       // leave a gap
        }
        String name = EDGE_BUILDINGS[r.nextInt(EDGE_BUILDINGS.length)];
        Spatial model = loadModel("Models/City/" + name + ".glb");
        if (model == null) {
            return;
        }
        float yaw;
        switch (edge) {
            case 0: yaw = FastMath.PI; break;            // faces -Z (towards the road)
            case 1: yaw = 0f; break;                     // faces +Z
            case 2: yaw = -FastMath.HALF_PI; break;      // faces -X
            default: yaw = FastMath.HALF_PI; break;      // faces +X
        }
        Node holder = new Node("Building");
        Node rot = new Node("Rot");
        rot.attachChild(model);
        rot.setLocalRotation(new Quaternion().fromAngleAxis(yaw, Vector3f.UNIT_Y));
        holder.attachChild(rot);
        holder.updateGeometricState();
        BoundingBox bb = (BoundingBox) holder.getWorldBound();
        float ex = Math.max(0.2f, bb.getXExtent());
        float ez = Math.max(0.2f, bb.getZExtent());
        boolean alongX = edge < 2;
        float width = (alongX ? ex : ez) * 2f;
        float depth = (alongX ? ez : ex) * 2f;
        float maxWidth = alongX ? 28f : 22f;
        float scale = FastMath.clamp(Math.min(maxWidth / width, 14f / depth), 3.5f, 16f);
        holder.setLocalScale(scale);

        float halfD = depth * scale / 2f;
        float lx;
        float lz;
        float edgeGap = ROAD_HALF + 3f;
        switch (edge) {
            case 0: lx = ROAD_HALF + t; lz = edgeGap + halfD; break;
            case 1: lx = ROAD_HALF + t; lz = P - edgeGap - halfD; break;
            case 2: lx = edgeGap + halfD; lz = ROAD_HALF + t; break;
            default: lx = P - edgeGap - halfD; lz = ROAD_HALF + t; break;
        }
        holder.setLocalTranslation(lx, SLAB_TOP, lz);
        cell.node.attachChild(holder);

        float hx = ex * scale * 0.92f;
        float hz = ez * scale * 0.92f;
        float wx = cell.i * P + lx;
        float wz = cell.j * P + lz;
        cell.rects.add(new float[]{wx - hx, wx + hx, wz - hz, wz + hz});
    }

    private void addCenterBuilding(Cell cell, Random r) {
        String name = CENTER_BUILDINGS[r.nextInt(CENTER_BUILDINGS.length)];
        Spatial model = loadModel("Models/City/" + name + ".glb");
        if (model == null) {
            return;
        }
        Node holder = new Node("Tower");
        Node rot = new Node("Rot");
        rot.attachChild(model);
        float yaw = r.nextInt(4) * FastMath.HALF_PI;
        rot.setLocalRotation(new Quaternion().fromAngleAxis(yaw, Vector3f.UNIT_Y));
        holder.attachChild(rot);
        holder.updateGeometricState();
        BoundingBox bb = (BoundingBox) holder.getWorldBound();
        float ex = Math.max(0.2f, bb.getXExtent());
        float ez = Math.max(0.2f, bb.getZExtent());
        float scale = FastMath.clamp(Math.min(15f / (ex * 2f), 15f / (ez * 2f)), 4f, 12f);
        holder.setLocalScale(scale);
        holder.setLocalTranslation(P / 2f, SLAB_TOP, P / 2f);
        cell.node.attachChild(holder);
        float hx = ex * scale * 0.92f;
        float hz = ez * scale * 0.92f;
        float wx = cell.i * P + P / 2f;
        float wz = cell.j * P + P / 2f;
        cell.rects.add(new float[]{wx - hx, wx + hx, wz - hz, wz + hz});
    }

    // ================================================================= city materials
    private Material texMaterial(Texture2D tex, float spec, float shininess, float diffuse) {
        Material m = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        m.setTexture("DiffuseMap", tex);
        m.setBoolean("UseMaterialColors", true);
        m.setColor("Diffuse", new ColorRGBA(diffuse, diffuse, diffuse, 1f));
        m.setColor("Ambient", new ColorRGBA(diffuse * 0.6f, diffuse * 0.6f, diffuse * 0.6f, 1f));
        m.setColor("Specular", new ColorRGBA(spec, spec, spec, 1f));
        m.setFloat("Shininess", shininess);
        return m;
    }

    private void initCityMaterials() {
        // textured surfaces (procedural, tile every 4 / 8 / 10 m in world space)
        matAsphalt = texMaterial(Textures.asphalt(38, 11L), 0.30f, 20f, 0.95f);
        matLot = texMaterial(Textures.asphalt(54, 23L), 0.08f, 8f, 0.95f);
        matSidewalk = texMaterial(Textures.concrete(150, 5L), 0.06f, 6f, 0.75f);
        matGrass = texMaterial(Textures.grass(7L), 0f, 1f, 0.95f);
        matPavers = texMaterial(Textures.pavers(3L), 0.04f, 4f, 0.85f);

        matWater = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        matWater.setBoolean("UseMaterialColors", true);
        matWater.setColor("Diffuse", new ColorRGBA(0.12f, 0.34f, 0.48f, 1f));
        matWater.setColor("Ambient", new ColorRGBA(0.10f, 0.25f, 0.35f, 1f));
        matWater.setColor("Specular", new ColorRGBA(0.9f, 0.9f, 0.9f, 1f));
        matWater.setFloat("Shininess", 90f);

        // plain painted / coloured things
        matCurb = material(new ColorRGBA(0.66f, 0.66f, 0.64f, 1f));
        matWhite = material(new ColorRGBA(0.92f, 0.92f, 0.92f, 1f));
        matYellow = material(new ColorRGBA(0.95f, 0.78f, 0.15f, 1f));
        matPatchDark = material(new ColorRGBA(0.075f, 0.078f, 0.09f, 1f));
        matPatchLight = material(new ColorRGBA(0.20f, 0.205f, 0.22f, 1f));
        matManhole = material(new ColorRGBA(0.10f, 0.10f, 0.105f, 1f));
        matMetal = material(new ColorRGBA(0.22f, 0.24f, 0.26f, 1f));
        matTrunk = material(new ColorRGBA(0.30f, 0.20f, 0.12f, 1f));
        matRed = material(new ColorRGBA(0.75f, 0.08f, 0.06f, 1f));
        matBin = material(new ColorRGBA(0.12f, 0.30f, 0.20f, 1f));
        matWood = material(new ColorRGBA(0.45f, 0.30f, 0.16f, 1f));
        matGlass = material(new ColorRGBA(0.55f, 0.72f, 0.80f, 1f));
        matBusSign = material(new ColorRGBA(0.10f, 0.35f, 0.75f, 1f));
        matSoil = material(new ColorRGBA(0.22f, 0.15f, 0.10f, 1f));
        matLeaf[0] = material(new ColorRGBA(0.16f, 0.40f, 0.14f, 1f));
        matLeaf[1] = material(new ColorRGBA(0.22f, 0.48f, 0.16f, 1f));
        matLeaf[2] = material(new ColorRGBA(0.30f, 0.50f, 0.18f, 1f));

        matStain = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        matStain.setTexture("ColorMap", Textures.glowPool());
        matStain.setColor("Color", new ColorRGBA(0f, 0f, 0f, 0.20f));
        matStain.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        matStain.getAdditionalRenderState().setDepthWrite(false);

        // street lamps: glowing head + soft light pool on the road (both fade in at night)
        matLamp = unshaded(new ColorRGBA(0.5f, 0.5f, 0.45f, 1f));
        matPool = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        matPool.setTexture("ColorMap", Textures.glowPool());
        matPool.setColor("Color", new ColorRGBA(1f, 0.82f, 0.5f, 0f));
        matPool.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        matPool.getAdditionalRenderState().setDepthWrite(false);
    }

    // ================================================================= pedestrians
    private static class Ped {
        Node node;
        Node legL;
        Node legR;
        Node armL;
        Node armR;
        int ci = Integer.MIN_VALUE;
        int cj;
        int edge;
        float t;
        float dir = 1f;
        float d;
        float speed;
        float phase;
        // knocked-down state
        boolean hit;
        boolean hLying;
        float hTimer;
        float hpx, hpy, hpz;
        float hvx, hvy, hvz;
        float hax, hay, haz;
        float hsx, hsy, hsz;
    }

    private static final ColorRGBA[] PED_SHIRTS = {
            new ColorRGBA(0.75f, 0.12f, 0.10f, 1f), new ColorRGBA(0.15f, 0.30f, 0.70f, 1f),
            new ColorRGBA(0.85f, 0.80f, 0.20f, 1f), new ColorRGBA(0.20f, 0.55f, 0.28f, 1f),
            new ColorRGBA(0.90f, 0.90f, 0.88f, 1f), new ColorRGBA(0.10f, 0.10f, 0.12f, 1f),
            new ColorRGBA(0.55f, 0.20f, 0.60f, 1f), new ColorRGBA(0.90f, 0.50f, 0.15f, 1f)
    };
    private static final ColorRGBA[] PED_PANTS = {
            new ColorRGBA(0.12f, 0.14f, 0.25f, 1f), new ColorRGBA(0.10f, 0.10f, 0.11f, 1f),
            new ColorRGBA(0.35f, 0.30f, 0.22f, 1f), new ColorRGBA(0.20f, 0.22f, 0.28f, 1f)
    };
    private static final ColorRGBA[] PED_SKIN = {
            new ColorRGBA(0.93f, 0.75f, 0.62f, 1f), new ColorRGBA(0.78f, 0.58f, 0.43f, 1f),
            new ColorRGBA(0.55f, 0.38f, 0.27f, 1f), new ColorRGBA(0.36f, 0.24f, 0.17f, 1f)
    };

    private Node limb(float hx, float hy, float hz, ColorRGBA color, float px0, float py0) {
        Node n = new Node("Limb");
        n.attachChild(box("LimbBox", hx, hy, hz, new Vector3f(0, -hy, 0), color));
        n.setLocalTranslation(px0, py0, 0);
        return n;
    }

    private void buildPeds() {
        for (int n = 0; n < PED_COUNT; n++) {
            Ped p = new Ped();
            ColorRGBA shirt = PED_SHIRTS[rnd.nextInt(PED_SHIRTS.length)];
            ColorRGBA pants = PED_PANTS[rnd.nextInt(PED_PANTS.length)];
            ColorRGBA skin = PED_SKIN[rnd.nextInt(PED_SKIN.length)];
            p.node = new Node("Ped" + n);
            p.node.attachChild(box("Torso", 0.2f, 0.3f, 0.11f, new Vector3f(0, 1.15f, 0), shirt));
            p.node.attachChild(box("Head", 0.1f, 0.12f, 0.1f, new Vector3f(0, 1.62f, 0), skin));
            p.legL = limb(0.08f, 0.42f, 0.09f, pants, -0.1f, 0.85f);
            p.legR = limb(0.08f, 0.42f, 0.09f, pants, 0.1f, 0.85f);
            p.armL = limb(0.06f, 0.3f, 0.07f, shirt, -0.28f, 1.42f);
            p.armR = limb(0.06f, 0.3f, 0.07f, shirt, 0.28f, 1.42f);
            p.node.attachChild(p.legL);
            p.node.attachChild(p.legR);
            p.node.attachChild(p.armL);
            p.node.attachChild(p.armR);
            p.node.setCullHint(Spatial.CullHint.Always);
            p.speed = 1.1f + rnd.nextFloat() * 0.7f;
            rootNode.attachChild(p.node);
            peds.add(p);
        }
    }

    /** Puts a pedestrian on the pavement of a random loaded block that is not too close or too far. */
    private void respawnPed(Ped p) {
        List<Cell> candidates = new ArrayList<>();
        for (Cell c : cells.values()) {
            float dx = (c.i + 0.5f) * P - px;
            float dz = (c.j + 0.5f) * P - pz;
            float d2 = dx * dx + dz * dz;
            if (d2 > 25f * 25f && d2 < 140f * 140f) {
                candidates.add(c);
            }
        }
        if (candidates.isEmpty()) {
            p.ci = Integer.MIN_VALUE;
            p.node.setCullHint(Spatial.CullHint.Always);
            return;
        }
        Cell c = candidates.get(rnd.nextInt(candidates.size()));
        p.ci = c.i;
        p.cj = c.j;
        p.edge = rnd.nextInt(4);
        p.t = 3f + rnd.nextFloat() * 62f;
        p.dir = rnd.nextBoolean() ? 1f : -1f;
        p.d = 0.6f + rnd.nextFloat() * 1.8f;
        p.node.setCullHint(Spatial.CullHint.Inherit);
    }

    private void updatePeds(float tpf) {
        for (Ped p : peds) {
            if (p.hit) {
                updateHitPed(p, tpf);
                continue;
            }
            Cell c = p.ci == Integer.MIN_VALUE ? null : cells.get(cellKey(p.ci, p.cj));
            if (c != null) {
                float dx = (c.i + 0.5f) * P - px;
                float dz = (c.j + 0.5f) * P - pz;
                if (dx * dx + dz * dz > 170f * 170f) {
                    c = null;
                }
            }
            if (c == null) {
                respawnPed(p);
                if (p.ci == Integer.MIN_VALUE) {
                    continue;
                }
                c = cells.get(cellKey(p.ci, p.cj));
                if (c == null) {
                    p.ci = Integer.MIN_VALUE;
                    continue;
                }
            }
            p.t += p.dir * p.speed * tpf;
            if (p.t < 1.5f) {
                p.t = 1.5f;
                p.dir = 1f;
            } else if (p.t > 66.5f) {
                p.t = 66.5f;
                p.dir = -1f;
            }
            p.phase += p.speed * tpf * 4.5f;
            float wx = p.ci * P + edgeX(p.edge, p.t, p.d);
            float wz = p.cj * P + edgeZ(p.edge, p.t, p.d);
            p.node.setLocalTranslation(wx, SIDEWALK_TOP, wz);
            float fx = p.edge < 2 ? p.dir : 0f;
            float fz = p.edge < 2 ? 0f : p.dir;
            p.node.setLocalRotation(new Quaternion().fromAngleAxis((float) Math.atan2(fx, fz), Vector3f.UNIT_Y));
            float sw = FastMath.sin(p.phase) * 0.6f;
            p.legL.setLocalRotation(new Quaternion().fromAngleAxis(sw, Vector3f.UNIT_X));
            p.legR.setLocalRotation(new Quaternion().fromAngleAxis(-sw, Vector3f.UNIT_X));
            p.armL.setLocalRotation(new Quaternion().fromAngleAxis(-sw, Vector3f.UNIT_X));
            p.armR.setLocalRotation(new Quaternion().fromAngleAxis(sw, Vector3f.UNIT_X));
            checkPedHit(p, wx, wz);
        }
    }

    // ================================================================= crash damage
    private static final String[] DEBRIS_MODELS = {
            "debris-bumper", "debris-door", "debris-door-window", "debris-tire", "debris-plate-a",
            "debris-plate-b", "debris-spoiler-a", "debris-spoiler-b", "debris-plate-small-a"
    };

    private static class Debris {
        Node node;
        final Vector3f vel = new Vector3f();
        float sx;
        float sy;
        float sz;
        float life;
        boolean still;
    }

    private void buildSmoke() {
        smoke = new ParticleEmitter("Smoke", ParticleMesh.Type.Triangle, 50);
        Material pm = new Material(assetManager, "Common/MatDefs/Misc/Particle.j3md");
        pm.setTexture("Texture", Textures.glowPool());
        pm.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        smoke.setMaterial(pm);
        smoke.setImagesX(1);
        smoke.setImagesY(1);
        smoke.setStartColor(new ColorRGBA(0.22f, 0.22f, 0.22f, 0.65f));
        smoke.setEndColor(new ColorRGBA(0.55f, 0.55f, 0.55f, 0f));
        smoke.setStartSize(0.5f);
        smoke.setEndSize(2.8f);
        smoke.setGravity(0f, 0f, 0f);
        smoke.getParticleInfluencer().setInitialVelocity(new Vector3f(0f, 2.0f, 0f));
        smoke.getParticleInfluencer().setVelocityVariation(0.45f);
        smoke.setLowLife(0.9f);
        smoke.setHighLife(2.0f);
        smoke.setParticlesPerSec(0f);
        smoke.setShadowMode(ShadowMode.Off);
        rootNode.attachChild(smoke);
    }

    private void updateSmoke() {
        if (smoke == null) {
            return;
        }
        float fx = FastMath.sin(onFoot ? carHeading : heading);
        float fz = FastMath.cos(onFoot ? carHeading : heading);
        smoke.setLocalTranslation((onFoot ? carX : px) + fx * 1.8f, 1.0f, (onFoot ? carZ : pz) + fz * 1.8f);
        smoke.setParticlesPerSec(playerDamage > 0.25f ? 6f + 40f * playerDamage : 0f);
    }

    private void addDamage(float intensity) {
        playerDamage = Math.min(1f, playerDamage + intensity * 0.22f);
        shake = Math.max(shake, 0.25f + 0.5f * intensity);
    }

    /** Car-to-car crash: both cars get dented, parts fly off, the player takes damage. */
    private void crashFx(Car c) {
        float mx = (px + c.wx) / 2f;
        float mz = (pz + c.wz) / 2f;
        Vector3f impact = new Vector3f(mx, 0.7f, mz);
        float inten = FastMath.clamp((Math.abs(speed) + c.speed * 0.5f) / 35f, 0.15f, 1f);
        dentNode(playerCar, impact, new Vector3f(px - mx, 0f, pz - mz), inten);
        dentNode(c.node, impact, new Vector3f(c.wx - mx, 0f, c.wz - mz), inten);
        spawnDebris(impact, new Vector3f(px - c.wx, 0f, pz - c.wz), 1 + (int) (inten * 3f));
        addDamage(inten);
    }

    /** Crash into a building / obstacle: the end of the car that hit gets crumpled. */
    private void wallFx() {
        float fx = FastMath.sin(heading);
        float fz = FastMath.cos(heading);
        float sgn = speed >= 0f ? 1f : -1f;
        Vector3f impact = new Vector3f(px + fx * sgn * 2.0f, 0.7f, pz + fz * sgn * 2.0f);
        float inten = FastMath.clamp(Math.abs(speed) / 40f, 0.15f, 1f);
        dentNode(playerCar, impact, new Vector3f(-fx * sgn, 0f, -fz * sgn), inten);
        spawnDebris(impact, new Vector3f(-fx * sgn, 0f, -fz * sgn), 1 + (int) (inten * 3f));
        addDamage(inten);
    }

    /**
     * Crumples a car: every vertex near the impact point is pushed in the push direction (plus a little
     * noise and sag), so the dent is real geometry. Meshes are cloned per car so other cars stay intact.
     */
    private void dentNode(Spatial target, Vector3f impact, Vector3f push, float strength) {
        target.updateGeometricState();
        Vector3f pd = new Vector3f(push.x, 0f, push.z);
        if (pd.lengthSquared() < 0.0001f) {
            pd.set(0f, 0f, 1f);
        }
        pd.normalizeLocal();
        dentRec(target, impact, pd, strength);
    }

    private void dentRec(Spatial s, Vector3f impact, Vector3f pd, float strength) {
        if (s instanceof Node) {
            for (Spatial c : ((Node) s).getChildren()) {
                dentRec(c, impact, pd, strength);
            }
        } else if (s instanceof Geometry) {
            try {
                dentGeometry((Geometry) s, impact, pd, strength);
            } catch (RuntimeException e) {
                // unusual mesh layout: skip the dent, keep the game running
            }
        }
    }

    private void dentGeometry(Geometry g, Vector3f impact, Vector3f pd, float strength) {
        String nm = g.getName();
        if (nm != null && (nm.startsWith("Head") || nm.startsWith("Tail"))) {
            return;
        }
        for (Spatial a = g; a != null; a = a.getParent()) {
            String an = a.getName();
            if (an != null && an.contains("wheel")) {
                return;
            }
        }
        Mesh mesh = g.getMesh();
        if (mesh.getBuffer(VertexBuffer.Type.Position) == null) {
            return;
        }
        if (g.getUserData("dented") == null) {
            mesh = mesh.deepClone();
            g.setMesh(mesh);
            g.setUserData("dented", Boolean.TRUE);
        }
        VertexBuffer vb = mesh.getBuffer(VertexBuffer.Type.Position);
        if (!(vb.getData() instanceof FloatBuffer)) {
            return;
        }
        FloatBuffer fb = (FloatBuffer) vb.getData();
        Transform t = g.getWorldTransform();
        float radius = 1.7f;
        Vector3f v = new Vector3f();
        Vector3f w = new Vector3f();
        boolean changed = false;
        int count = fb.limit() / 3;
        for (int i = 0; i < count; i++) {
            v.set(fb.get(i * 3), fb.get(i * 3 + 1), fb.get(i * 3 + 2));
            t.transformVector(v, w);
            float dx = w.x - impact.x;
            float dy = w.y - impact.y;
            float dz = w.z - impact.z;
            float dist = FastMath.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist >= radius) {
                continue;
            }
            float f = 1f - dist / radius;
            f *= f;
            float h = (float) (Math.sin(i * 12.9898) * 43758.5453);
            h = h - (float) Math.floor(h);
            float h2 = (float) (Math.sin(i * 78.233 + 1.7) * 12345.6789);
            h2 = h2 - (float) Math.floor(h2);
            float n = 0.6f + 0.8f * h;
            float push = strength * 0.9f * f * n;
            w.x += pd.x * push - pd.z * (h2 - 0.5f) * strength * 0.35f * f;
            w.z += pd.z * push + pd.x * (h2 - 0.5f) * strength * 0.35f * f;
            w.y -= strength * 0.12f * f * n;
            t.transformInverseVector(w, v);
            fb.put(i * 3, v.x);
            fb.put(i * 3 + 1, v.y);
            fb.put(i * 3 + 2, v.z);
            changed = true;
        }
        if (changed) {
            vb.updateData(fb);
            mesh.updateBound();
        }
    }

    private void spawnDebris(Vector3f at, Vector3f dir, int count) {
        Vector3f dn = new Vector3f(dir.x, 0f, dir.z);
        if (dn.lengthSquared() < 0.0001f) {
            dn.set(0f, 0f, 1f);
        }
        dn.normalizeLocal();
        for (int k = 0; k < count; k++) {
            String name = DEBRIS_MODELS[rnd.nextInt(DEBRIS_MODELS.length)];
            Spatial m = loadModel("Models/CarKit/" + name + ".glb");
            if (m == null) {
                continue;
            }
            m.setLocalScale(CAR_SCALE);
            Debris d = new Debris();
            d.node = new Node("Debris");
            d.node.attachChild(m);
            d.node.setLocalTranslation(at.x, at.y, at.z);
            float sp = 4f + rnd.nextFloat() * 7f;
            d.vel.set(dn.x * sp + (rnd.nextFloat() - 0.5f) * 5f, 3f + rnd.nextFloat() * 5f,
                    dn.z * sp + (rnd.nextFloat() - 0.5f) * 5f);
            d.sx = (rnd.nextFloat() - 0.5f) * 16f;
            d.sy = (rnd.nextFloat() - 0.5f) * 16f;
            d.sz = (rnd.nextFloat() - 0.5f) * 16f;
            rootNode.attachChild(d.node);
            debris.add(d);
            if (debris.size() > 40) {
                Debris old = debris.remove(0);
                old.node.removeFromParent();
            }
        }
    }

    private void updateDebris(float tpf) {
        for (int i = debris.size() - 1; i >= 0; i--) {
            Debris d = debris.get(i);
            d.life += tpf;
            if (d.life > 16f) {
                d.node.removeFromParent();
                debris.remove(i);
                continue;
            }
            if (d.still) {
                continue;
            }
            d.vel.y -= 22f * tpf;
            Vector3f pos = d.node.getLocalTranslation();
            float nx = pos.x + d.vel.x * tpf;
            float ny = pos.y + d.vel.y * tpf;
            float nz = pos.z + d.vel.z * tpf;
            if (ny <= 0.15f) {
                ny = 0.15f;
                if (Math.abs(d.vel.y) > 1.5f) {
                    d.vel.y = -d.vel.y * 0.4f;
                    d.vel.x *= 0.6f;
                    d.vel.z *= 0.6f;
                    d.sx *= 0.6f;
                    d.sy *= 0.6f;
                    d.sz *= 0.6f;
                } else {
                    d.still = true;
                }
            }
            d.node.setLocalTranslation(nx, ny, nz);
            d.node.rotate(d.sx * tpf, d.sy * tpf, d.sz * tpf);
        }
    }

    private void clearDebris() {
        for (Debris d : debris) {
            d.node.removeFromParent();
        }
        debris.clear();
    }

    // ----------------------------------------------------------------- pedestrians hit by the car
    private void checkPedHit(Ped p, float wx, float wz) {
        if (state != State.PLAYING || Math.abs(speed) < 5f) {
            return;
        }
        float fx = FastMath.sin(heading);
        float fz = FastMath.cos(heading);
        float sgn = speed >= 0f ? 1f : -1f;
        float cx = px + fx * sgn * 1.4f;
        float cz = pz + fz * sgn * 1.4f;
        float dx = wx - cx;
        float dz = wz - cz;
        if (dx * dx + dz * dz > 1.7f * 1.7f) {
            return;
        }
        float sp = Math.abs(speed);
        p.hit = true;
        p.hLying = false;
        p.hTimer = 0f;
        p.hpx = wx;
        p.hpy = SIDEWALK_TOP + 0.4f;
        p.hpz = wz;
        p.hvx = FastMath.clamp(fx * sgn * sp * 0.9f + (rnd.nextFloat() - 0.5f) * 3f, -25f, 25f);
        p.hvz = FastMath.clamp(fz * sgn * sp * 0.9f + (rnd.nextFloat() - 0.5f) * 3f, -25f, 25f);
        p.hvy = 4f + sp * 0.18f + rnd.nextFloat() * 2f;
        p.hax = 0f;
        p.hay = heading;
        p.haz = 0f;
        p.hsx = (rnd.nextFloat() - 0.5f) * 14f;
        p.hsy = (rnd.nextFloat() - 0.5f) * 6f;
        p.hsz = (rnd.nextFloat() - 0.5f) * 14f;
        p.legL.setLocalRotation(new Quaternion().fromAngles((rnd.nextFloat() - 0.5f) * 2.4f, 0f, 0f));
        p.legR.setLocalRotation(new Quaternion().fromAngles((rnd.nextFloat() - 0.5f) * 2.4f, 0f, 0f));
        p.armL.setLocalRotation(new Quaternion().fromAngles((rnd.nextFloat() - 0.5f) * 3f, 0f, -1.2f));
        p.armR.setLocalRotation(new Quaternion().fromAngles((rnd.nextFloat() - 0.5f) * 3f, 0f, 1.2f));
        pedHits++;
        showMessage("HIT!  -150", new ColorRGBA(1f, 0.6f, 0.1f, 1f), 1.2f);
        shake = Math.max(shake, 0.25f);
        speed *= 0.92f;
    }

    private void updateHitPed(Ped p, float tpf) {
        float ddx = p.hpx - px;
        float ddz = p.hpz - pz;
        if (ddx * ddx + ddz * ddz > 170f * 170f) {
            resetPed(p);
            return;
        }
        if (!p.hLying) {
            p.hvy -= 22f * tpf;
            p.hpx += p.hvx * tpf;
            p.hpy += p.hvy * tpf;
            p.hpz += p.hvz * tpf;
            p.hax += p.hsx * tpf;
            p.hay += p.hsy * tpf;
            p.haz += p.hsz * tpf;
            if (p.hpy <= 0.1f) {
                p.hpy = 0.1f;
                if (Math.abs(p.hvy) > 2.2f) {
                    p.hvy = -p.hvy * 0.35f;
                    p.hvx *= 0.55f;
                    p.hvz *= 0.55f;
                    p.hsx *= 0.6f;
                    p.hsy *= 0.6f;
                    p.hsz *= 0.6f;
                } else {
                    p.hLying = true;
                }
            }
            p.node.setLocalTranslation(p.hpx, p.hpy, p.hpz);
            p.node.setLocalRotation(new Quaternion().fromAngles(p.hax, p.hay, p.haz));
        } else {
            float slow = Math.max(0f, 1f - 4f * tpf);
            p.hvx *= slow;
            p.hvz *= slow;
            p.hpx += p.hvx * tpf;
            p.hpz += p.hvz * tpf;
            p.hTimer += tpf;
            p.node.setLocalTranslation(p.hpx, 0.2f, p.hpz);
            p.node.setLocalRotation(new Quaternion().fromAngles(-FastMath.HALF_PI, p.hay, 0f));
            if (p.hTimer > 6f) {
                resetPed(p);
            }
        }
    }

    private void resetPed(Ped p) {
        p.hit = false;
        p.node.setLocalRotation(new Quaternion());
        p.legL.setLocalRotation(new Quaternion());
        p.legR.setLocalRotation(new Quaternion());
        p.armL.setLocalRotation(new Quaternion());
        p.armR.setLocalRotation(new Quaternion());
        p.ci = Integer.MIN_VALUE;
        respawnPed(p);
    }

    // ================================================================= on foot: hero + weapons
    private static class Weapon {
        final String name;
        final float rate;        // seconds between shots
        final int mag;
        final int reserve;
        final float reload;
        final float range;
        final int pellets;
        final float spread;      // radians
        final boolean auto;
        final float knock;       // how hard pedestrians are thrown
        final boolean rocket;
        final float len;         // gun model length
        final ColorRGBA color;

        Weapon(String name, float rate, int mag, int reserve, float reload, float range, int pellets,
               float spread, boolean auto, float knock, boolean rocket, float len, ColorRGBA color) {
            this.name = name;
            this.rate = rate;
            this.mag = mag;
            this.reserve = reserve;
            this.reload = reload;
            this.range = range;
            this.pellets = pellets;
            this.spread = spread;
            this.auto = auto;
            this.knock = knock;
            this.rocket = rocket;
            this.len = len;
            this.color = color;
        }
    }

    private static final Weapon[] WEAPONS = {
            new Weapon("PISTOL", 0.30f, 12, 72, 1.2f, 70f, 1, 0.012f, false, 4f, false, 0.35f,
                    new ColorRGBA(0.15f, 0.15f, 0.17f, 1f)),
            new Weapon("SMG", 0.075f, 30, 180, 1.5f, 55f, 1, 0.045f, true, 3f, false, 0.5f,
                    new ColorRGBA(0.12f, 0.12f, 0.14f, 1f)),
            new Weapon("SHOTGUN", 0.85f, 6, 36, 2.0f, 28f, 8, 0.13f, false, 7f, false, 0.9f,
                    new ColorRGBA(0.25f, 0.17f, 0.10f, 1f)),
            new Weapon("ASSAULT RIFLE", 0.11f, 30, 150, 1.8f, 100f, 1, 0.02f, true, 4f, false, 0.85f,
                    new ColorRGBA(0.14f, 0.16f, 0.12f, 1f)),
            new Weapon("SNIPER RIFLE", 1.2f, 5, 30, 2.4f, 220f, 1, 0.002f, false, 8f, false, 1.1f,
                    new ColorRGBA(0.10f, 0.12f, 0.10f, 1f)),
            new Weapon("ROCKET LAUNCHER", 1.6f, 1, 8, 2.2f, 140f, 1, 0f, false, 0f, true, 0.9f,
                    new ColorRGBA(0.28f, 0.34f, 0.18f, 1f))
    };

    private static class Tracer {
        Spatial s;
        float life;
    }

    private static class Rocket {
        Node n;
        float x;
        float z;
        float dx;
        float dz;
        float dist;
    }

    private static class Boom {
        Geometry g;
        Material m;
        float t;
    }

    private static class Hit {
        int type;        // 0 nothing, 1 pedestrian, 2 traffic car, 3 solid object, 4 the player's parked car
        float t;
        Ped ped;
        Car car;
    }

    private boolean onFoot;
    private float carX;
    private float carZ;
    private float carHeading;
    private Node hero;
    private Node heroLegL;
    private Node heroLegR;
    private Node heroArmL;
    private Node heroArmR;
    private Node gunNode;
    private Geometry muzzleFlash;
    private float heroVx;
    private float heroVz;
    private float heroY;
    private float heroVy;
    private float heroPhase;
    private float heroKnock;
    private float heroHealth = 100f;
    private boolean heroDead;
    private float deadTimer;
    private float heroHitCooldown;
    private boolean fireHeld;
    private boolean fireEdge;
    private boolean sprint;
    private boolean jumpRequested;
    private int weaponIndex;
    private final int[] ammoMag = new int[WEAPONS.length];
    private final int[] ammoReserve = new int[WEAPONS.length];
    private float fireCooldown;
    private float reloadTimer;
    private float muzzleTimer;
    private Material tracerMat;
    private final List<Tracer> tracers = new ArrayList<>();
    private final List<Rocket> rockets = new ArrayList<>();
    private final List<Boom> booms = new ArrayList<>();
    private BitmapText hudWeapon;
    private Node crosshair;

    // ---- optional animated character model (any .glb with a skeleton, e.g. from Mixamo / Quaternius /
    // Ready Player Me). Put it at src/main/resources/Models/Characters/hero.glb - if the file is missing
    // the built-in character below is used instead.
    private static final String HERO_MODEL = "Models/Characters/hero.glb";
    private static final float HERO_SCALE = 1.0f;       // make the model ~1.8 units tall
    private static final float HERO_YAW_FIX = 0f;       // set to FastMath.PI if the model walks backwards
    private static final boolean HERO_USE_MODEL = true; // false = always use the built-in character
    private static final boolean HERO_ANIMATED = false; // false = no skeleton animation (safe, static pose)
    private static final float HERO_HEIGHT = 1.8f;      // the model is auto-resized to this height
    private Node heroBody;
    private AnimComposer heroAnim;
    private Node heroModel;
    private String heroIdle;
    private String heroWalk;
    private String heroRun;
    private String heroClip;

    private Node armNode(ColorRGBA sleeve, ColorRGBA skin, float px0) {
        Node n = new Node("Arm");
        n.attachChild(box("Sleeve", 0.065f, 0.17f, 0.075f, new Vector3f(0, -0.17f, 0), sleeve));
        n.attachChild(box("Forearm", 0.052f, 0.2f, 0.058f, new Vector3f(0, -0.5f, 0), skin));
        n.attachChild(box("Hand", 0.05f, 0.07f, 0.06f, new Vector3f(0, -0.72f, 0.01f), skin));
        n.setLocalTranslation(px0, 1.42f, 0);
        return n;
    }

    private Node legNode(ColorRGBA trousers, float px0) {
        Node n = limb(0.085f, 0.42f, 0.095f, trousers, px0, 0.85f);
        n.attachChild(box("Shoe", 0.09f, 0.05f, 0.16f, new Vector3f(0, -0.81f, 0.05f),
                new ColorRGBA(0.9f, 0.9f, 0.88f, 1f)));
        n.attachChild(box("Sole", 0.092f, 0.018f, 0.165f, new Vector3f(0, -0.862f, 0.05f),
                new ColorRGBA(0.12f, 0.12f, 0.12f, 1f)));
        return n;
    }

    private void softwareSkinning(Spatial sp) {
        if (!HERO_ANIMATED) {
            while (sp.getControl(SkinningControl.class) != null) {
                sp.removeControl(SkinningControl.class);
            }
            while (sp.getControl(AnimComposer.class) != null) {
                sp.removeControl(AnimComposer.class);
            }
        }
        SkinningControl sc = sp.getControl(SkinningControl.class);
        if (sc != null) {
            sc.setHardwareSkinningPreferred(false);
        }
        if (sp instanceof Node) {
            for (Spatial ch : ((Node) sp).getChildren()) {
                softwareSkinning(ch);
            }
        }
    }

    /**
     * glTF models come with PBR materials whose shaders can fail on some setups (that is what closed the
     * game the moment the character appeared). Replace every material with the plain Lighting shader,
     * keeping the colour / texture, and never cull the skinned meshes by their (stale) bounds.
     */
    private void simplifyModel(Spatial sp) {
        if (sp instanceof Geometry) {
            Geometry g = (Geometry) sp;
            ColorRGBA c = new ColorRGBA(0.75f, 0.75f, 0.75f, 1f);
            Texture tex = null;
            try {
                Material old = g.getMaterial();
                if (old != null) {
                    MatParam bc = old.getParam("BaseColor");
                    if (bc == null) {
                        bc = old.getParam("Diffuse");
                    }
                    if (bc != null && bc.getValue() instanceof ColorRGBA) {
                        c = ((ColorRGBA) bc.getValue()).clone();
                    }
                    MatParam bt = old.getParam("BaseColorMap");
                    if (bt == null) {
                        bt = old.getParam("DiffuseMap");
                    }
                    if (bt != null && bt.getValue() instanceof Texture) {
                        tex = (Texture) bt.getValue();
                    }
                }
            } catch (RuntimeException e) {
                // keep the default grey
            }
            Material m = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
            m.setBoolean("UseMaterialColors", true);
            m.setColor("Diffuse", c);
            m.setColor("Ambient", c.mult(0.6f));
            m.setColor("Specular", new ColorRGBA(0.12f, 0.12f, 0.12f, 1f));
            m.setFloat("Shininess", 12f);
            if (tex != null) {
                m.setTexture("DiffuseMap", tex);
            }
            g.setMaterial(m);
            g.setCullHint(Spatial.CullHint.Never);
        }
        if (sp instanceof Node) {
            for (Spatial ch : ((Node) sp).getChildren()) {
                simplifyModel(ch);
            }
        }
    }

    private AnimComposer findComposer(Spatial sp) {
        AnimComposer c = sp.getControl(AnimComposer.class);
        if (c != null) {
            return c;
        }
        if (sp instanceof Node) {
            for (Spatial ch : ((Node) sp).getChildren()) {
                AnimComposer r = findComposer(ch);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private void buildHero() {
        hero = new Node("Hero");
        heroBody = new Node("HeroBody");
        hero.attachChild(heroBody);
        ColorRGBA jacket = new ColorRGBA(0.10f, 0.28f, 0.24f, 1f);       // dark green jacket
        ColorRGBA shirt = new ColorRGBA(0.82f, 0.82f, 0.80f, 1f);
        ColorRGBA jeans = new ColorRGBA(0.14f, 0.20f, 0.34f, 1f);
        ColorRGBA skin = PED_SKIN[1];
        ColorRGBA hair = new ColorRGBA(0.07f, 0.05f, 0.04f, 1f);
        ColorRGBA dark = new ColorRGBA(0.06f, 0.06f, 0.07f, 1f);

        // torso: chest, waist, belt, shirt collar, neck
        heroBody.attachChild(box("Chest", 0.25f, 0.21f, 0.13f, new Vector3f(0, 1.27f, 0), jacket));
        heroBody.attachChild(box("Waist", 0.21f, 0.14f, 0.115f, new Vector3f(0, 0.99f, 0), jacket));
        heroBody.attachChild(box("Belt", 0.215f, 0.025f, 0.12f, new Vector3f(0, 0.87f, 0), dark));
        heroBody.attachChild(box("Collar", 0.13f, 0.03f, 0.105f, new Vector3f(0, 1.5f, 0), shirt));
        heroBody.attachChild(box("Neck", 0.055f, 0.05f, 0.055f, new Vector3f(0, 1.55f, 0), skin));
        // head: face, hair, nose, eyes, brows, ears
        heroBody.attachChild(box("Head", 0.1f, 0.125f, 0.105f, new Vector3f(0, 1.7f, 0), skin));
        heroBody.attachChild(box("HairTop", 0.108f, 0.04f, 0.112f, new Vector3f(0, 1.83f, -0.005f), hair));
        heroBody.attachChild(box("HairBack", 0.108f, 0.1f, 0.03f, new Vector3f(0, 1.74f, -0.09f), hair));
        heroBody.attachChild(box("Nose", 0.018f, 0.03f, 0.025f, new Vector3f(0, 1.68f, 0.115f), skin));
        heroBody.attachChild(box("EyeL", 0.016f, 0.01f, 0.006f, new Vector3f(-0.04f, 1.72f, 0.107f), dark));
        heroBody.attachChild(box("EyeR", 0.016f, 0.01f, 0.006f, new Vector3f(0.04f, 1.72f, 0.107f), dark));
        heroBody.attachChild(box("BrowL", 0.03f, 0.007f, 0.006f, new Vector3f(-0.04f, 1.745f, 0.108f), hair));
        heroBody.attachChild(box("BrowR", 0.03f, 0.007f, 0.006f, new Vector3f(0.04f, 1.745f, 0.108f), hair));
        heroBody.attachChild(box("EarL", 0.012f, 0.03f, 0.02f, new Vector3f(-0.112f, 1.7f, 0), skin));
        heroBody.attachChild(box("EarR", 0.012f, 0.03f, 0.02f, new Vector3f(0.112f, 1.7f, 0), skin));
        // backpack with straps
        heroBody.attachChild(box("Pack", 0.17f, 0.22f, 0.07f, new Vector3f(0, 1.22f, -0.2f), dark));
        heroBody.attachChild(box("PackPocket", 0.12f, 0.08f, 0.03f, new Vector3f(0, 1.1f, -0.27f),
                new ColorRGBA(0.2f, 0.2f, 0.22f, 1f)));
        heroBody.attachChild(box("StrapL", 0.02f, 0.2f, 0.02f, new Vector3f(-0.13f, 1.3f, 0.125f), dark));
        heroBody.attachChild(box("StrapR", 0.02f, 0.2f, 0.02f, new Vector3f(0.13f, 1.3f, 0.125f), dark));

        heroLegL = legNode(jeans, -0.105f);
        heroLegR = legNode(jeans, 0.105f);
        heroArmL = armNode(jacket, skin, -0.32f);
        heroArmR = armNode(jacket, skin, 0.32f);
        heroBody.attachChild(heroLegL);
        heroBody.attachChild(heroLegR);
        heroBody.attachChild(heroArmL);
        heroBody.attachChild(heroArmR);

        gunNode = new Node("Gun");
        gunNode.setLocalTranslation(0.32f, 1.2f, 0.3f);
        hero.attachChild(gunNode);
        muzzleFlash = glowBox("Flash", 0.1f, 0.1f, 0.1f, new Vector3f(0, 0, 0),
                unshaded(new ColorRGBA(1f, 0.85f, 0.3f, 1f)));
        tracerMat = unshaded(new ColorRGBA(1f, 0.9f, 0.5f, 1f));

        // optional external character model
        try {
            if (HERO_USE_MODEL && assetManager.locateAsset(new ModelKey(HERO_MODEL)) != null) {
                Spatial m = assetManager.loadModel(HERO_MODEL);
                softwareSkinning(m);                    // GPU skinning breaks many converted FBX rigs
                simplifyModel(m);
                Node holder = new Node("HeroModel");
                holder.attachChild(m);
                holder.setLocalRotation(new Quaternion().fromAngleAxis(HERO_YAW_FIX, Vector3f.UNIT_Y));
                hero.attachChild(holder);
                heroModel = holder;
                // measure the model and fix size / lying-down orientation automatically
                hero.updateGeometricState();
                Object wb = m.getWorldBound();
                if (wb instanceof BoundingBox) {
                    BoundingBox bb = (BoundingBox) wb;
                    float ex = bb.getXExtent() * 2f;
                    float ey = bb.getYExtent() * 2f;
                    float ez = bb.getZExtent() * 2f;
                    System.out.println("Hero model size before fixing: " + ex + " x " + ey + " x " + ez);
                    if (ez > ey * 1.5f && ez > ex * 1.5f) {            // Z-up model lying on its back
                        m.setLocalRotation(new Quaternion().fromAngleAxis(-FastMath.HALF_PI, Vector3f.UNIT_X));
                        float tmp = ey;
                        ey = ez;
                        ez = tmp;
                    }
                    if (ey > 0.0001f && ey < 1000000f) {
                        m.setLocalScale(HERO_SCALE * HERO_HEIGHT / ey);
                    } else {
                        m.setLocalScale(HERO_SCALE * 0.01f);
                    }
                } else {
                    m.setLocalScale(HERO_SCALE * 0.01f);    // Mixamo exports are in centimetres
                }
                m = holder;
                heroBody.setCullHint(Spatial.CullHint.Always);
                heroAnim = findComposer(m);
                if (heroAnim != null) {
                    System.out.println("Hero animation clips: " + heroAnim.getAnimClipsNames());
                    for (String n : heroAnim.getAnimClipsNames()) {
                        String l = n.toLowerCase();
                        if (l.contains("idle") && heroIdle == null) {
                            heroIdle = n;
                        }
                        if ((l.contains("run") || l.contains("sprint")) && heroRun == null) {
                            heroRun = n;
                        }
                        if (l.contains("walk") && heroWalk == null) {
                            heroWalk = n;
                        }
                    }
                    if (heroIdle == null && heroWalk == null && heroRun == null) {
                        for (String n : heroAnim.getAnimClipsNames()) {   // single unnamed clip: use it for everything
                            heroIdle = n;
                            break;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            System.out.println("Hero model could not be used, using built-in character: " + e);
            heroAnim = null;           // bad model file: keep the built-in character
            heroBody.setCullHint(Spatial.CullHint.Inherit);
            if (heroModel != null) {
                heroModel.removeFromParent();
            }
        }

        hero.setCullHint(Spatial.CullHint.Always);
        rootNode.attachChild(hero);
        resetWeapons();
        rebuildGun();
    }

    private void rebuildGun() {
        gunNode.detachAllChildren();
        Weapon w = WEAPONS[weaponIndex];
        if (w.rocket) {
            gunNode.attachChild(box("Tube", 0.08f, 0.08f, w.len * 0.55f,
                    new Vector3f(0, 0.05f, w.len * 0.25f), w.color));
            gunNode.attachChild(box("Grip", 0.03f, 0.1f, 0.04f, new Vector3f(0, -0.08f, 0.1f),
                    new ColorRGBA(0.1f, 0.1f, 0.1f, 1f)));
        } else {
            gunNode.attachChild(box("Receiver", 0.035f, 0.05f, w.len * 0.5f,
                    new Vector3f(0, 0, w.len * 0.5f), w.color));
            gunNode.attachChild(box("Grip", 0.03f, 0.09f, 0.03f, new Vector3f(0, -0.09f, 0.08f),
                    new ColorRGBA(0.08f, 0.08f, 0.08f, 1f)));
            if (weaponIndex == 4) {
                gunNode.attachChild(box("Scope", 0.03f, 0.03f, 0.14f, new Vector3f(0, 0.09f, 0.4f),
                        new ColorRGBA(0.05f, 0.05f, 0.06f, 1f)));
            }
            if (weaponIndex == 2) {
                gunNode.attachChild(box("Pump", 0.04f, 0.04f, 0.12f, new Vector3f(0, -0.05f, 0.5f),
                        new ColorRGBA(0.2f, 0.12f, 0.07f, 1f)));
            }
        }
        muzzleFlash.setLocalTranslation(0, 0, w.len + 0.12f);
        muzzleFlash.setCullHint(Spatial.CullHint.Always);
        gunNode.attachChild(muzzleFlash);
    }

    private void resetWeapons() {
        for (int i = 0; i < WEAPONS.length; i++) {
            ammoMag[i] = WEAPONS[i].mag;
            ammoReserve[i] = WEAPONS[i].reserve;
        }
        weaponIndex = 0;
        reloadTimer = 0f;
    }

    private void refillAmmo() {
        for (int i = 0; i < WEAPONS.length; i++) {
            ammoMag[i] = WEAPONS[i].mag;
            ammoReserve[i] = WEAPONS[i].reserve;
        }
        reloadTimer = 0f;
    }

    private void selectWeapon(int index) {
        if (index < 0 || index >= WEAPONS.length || index == weaponIndex) {
            return;
        }
        weaponIndex = index;
        reloadTimer = 0f;
        rebuildGun();
        showMessage(WEAPONS[index].name, new ColorRGBA(1f, 0.9f, 0.4f, 1f), 0.8f);
    }

    private void reloadWeapon() {
        Weapon w = WEAPONS[weaponIndex];
        if (reloadTimer > 0f || ammoMag[weaponIndex] >= w.mag || ammoReserve[weaponIndex] <= 0) {
            return;
        }
        reloadTimer = w.reload;
    }

    private void finishReload() {
        Weapon w = WEAPONS[weaponIndex];
        int take = Math.min(w.mag - ammoMag[weaponIndex], ammoReserve[weaponIndex]);
        ammoMag[weaponIndex] += take;
        ammoReserve[weaponIndex] -= take;
    }

    private void leaveFootMode() {
        onFoot = false;
        heroDead = false;
        heroHealth = 100f;
        heroVx = 0f;
        heroVz = 0f;
        heroY = 0f;
        heroVy = 0f;
        fireHeld = false;
        fireEdge = false;
        if (hero != null) {
            hero.setCullHint(Spatial.CullHint.Always);
        }
        refillAmmo();
    }

    /** F key: get out of the car (when slow) or back in (when close). */
    private void toggleVehicle() {
        if (state != State.PLAYING || heroDead) {
            return;
        }
        if (!onFoot) {
            if (Math.abs(speed) > 12f) {
                showMessage("TOO FAST TO GET OUT", ColorRGBA.Red, 1f);
                return;
            }
            carX = px;
            carZ = pz;
            carHeading = heading;
            speed = 0f;
            steering = 0f;
            float fx = FastMath.sin(heading);
            float fz = FastMath.cos(heading);
            px = carX + fz * 2.3f;          // step out on the driver's (left) side
            pz = carZ - fx * 2.3f;
            heroVx = 0f;
            heroVz = 0f;
            heroY = 0f;
            heroVy = 0f;
            onFoot = true;
            hero.setCullHint(Spatial.CullHint.Inherit);
            syncPlayerNode();
        } else {
            float dx = px - carX;
            float dz = pz - carZ;
            if (dx * dx + dz * dz > 4.5f * 4.5f) {
                showMessage("GET CLOSER TO THE CAR", ColorRGBA.Red, 1f);
                return;
            }
            px = carX;
            pz = carZ;
            heading = carHeading;
            speed = 0f;
            onFoot = false;
            hero.setCullHint(Spatial.CullHint.Always);
            refillAmmo();
            showMessage("AMMO RESTOCKED", new ColorRGBA(0.4f, 1f, 0.5f, 1f), 1f);
            syncPlayerNode();
        }
    }

    private void damageHero(float dmg) {
        if (heroDead) {
            return;
        }
        heroHealth -= dmg;
        shake = Math.max(shake, 0.5f);
        if (heroHealth <= 0f) {
            heroHealth = 0f;
            heroDead = true;
            deadTimer = 0f;
            fireHeld = false;
            showMessage("WASTED", ColorRGBA.Red, 3f);
        }
    }

    private void respawnHero() {
        heroDead = false;
        heroHealth = 100f;
        float fx = FastMath.sin(carHeading);
        float fz = FastMath.cos(carHeading);
        px = carX + fz * 2.3f;
        pz = carZ - fx * 2.3f;
        heroVx = 0f;
        heroVz = 0f;
        heading = carHeading;
        if (mission.timeLimit > 0f) {
            timeLeft = Math.max(1f, timeLeft - 10f);
        }
    }

    private void updateHero(float tpf) {
        cam.setFrustumPerspective(60f, (float) cam.getWidth() / cam.getHeight(), 0.5f, 420f);
        heroHitCooldown = Math.max(0f, heroHitCooldown - tpf);
        fireCooldown = Math.max(0f, fireCooldown - tpf);
        heroKnock = Math.max(0f, heroKnock - tpf);
        if (muzzleTimer > 0f) {
            muzzleTimer -= tpf;
            if (muzzleTimer <= 0f) {
                muzzleFlash.setCullHint(Spatial.CullHint.Always);
            }
        }
        if (heroDead) {
            deadTimer += tpf;
            hero.setLocalTranslation(px, 0.2f, pz);
            hero.setLocalRotation(new Quaternion().fromAngles(-FastMath.HALF_PI, heading, 0f));
            if (deadTimer > 3f) {
                respawnHero();
            }
            return;
        }
        if (reloadTimer > 0f) {
            reloadTimer -= tpf;
            if (reloadTimer <= 0f) {
                reloadTimer = 0f;
                finishReload();
            }
        }
        boolean drive = state == State.PLAYING;
        float fx = FastMath.sin(heading);
        float fz = FastMath.cos(heading);
        float rx = -fz;
        float rz = fx;
        float mf = drive ? ((accelerating ? 1f : 0f) - (braking ? 1f : 0f)) : 0f;
        float ms = drive ? ((right ? 1f : 0f) - (left ? 1f : 0f)) : 0f;
        float dxm = fx * mf + rx * ms;
        float dzm = fz * mf + rz * ms;
        float len = (float) Math.sqrt(dxm * dxm + dzm * dzm);
        if (len > 1f) {
            dxm /= len;
            dzm /= len;
        }
        float topSpeed = sprint ? 7.2f : 3.4f;
        if (mf < 0f) {
            topSpeed = 2.0f;
        }
        if (heroKnock <= 0f) {
            float k = Math.min(1f, 10f * tpf);
            heroVx += (dxm * topSpeed - heroVx) * k;
            heroVz += (dzm * topSpeed - heroVz) * k;
        }
        px += heroVx * tpf;
        pz += heroVz * tpf;

        if (jumpRequested) {
            jumpRequested = false;
            if (heroY <= 0.01f && drive) {
                heroVy = 6f;
            }
        }
        heroVy -= 20f * tpf;
        heroY += heroVy * tpf;
        if (heroY <= 0f) {
            heroY = 0f;
            if (heroVy < 0f) {
                heroVy = 0f;
            }
        }
        collideHero();

        // gun handling
        Weapon w = WEAPONS[weaponIndex];
        if (drive && fireHeld && reloadTimer <= 0f && fireCooldown <= 0f && (w.auto || fireEdge)) {
            fireWeapon(w);
            fireEdge = false;
        }

        // pose + walk cycle
        float moving = (float) Math.sqrt(heroVx * heroVx + heroVz * heroVz);
        heroPhase += moving * tpf * 1.7f;
        float amp = Math.min(0.9f, moving * 0.18f);
        float sw = FastMath.sin(heroPhase) * amp;
        if (heroAnim != null) {
            String want = moving > 5f && heroRun != null ? heroRun
                    : (moving > 0.4f && heroWalk != null ? heroWalk : heroIdle);
            if (want != null && !want.equals(heroClip)) {
                try {
                    heroAnim.setCurrentAction(want);
                } catch (RuntimeException e) {
                    heroAnim = null;
                }
                heroClip = want;
            }
        }
        heroLegL.setLocalRotation(new Quaternion().fromAngleAxis(sw, Vector3f.UNIT_X));
        heroLegR.setLocalRotation(new Quaternion().fromAngleAxis(-sw, Vector3f.UNIT_X));
        heroArmR.setLocalRotation(new Quaternion().fromAngleAxis(-1.5f, Vector3f.UNIT_X));    // holds the gun out
        heroArmL.setLocalRotation(new Quaternion().fromAngles(-1.3f, 0f, 0.35f));
        hero.setLocalTranslation(px, 0.15f + heroY, pz);
        hero.setLocalRotation(new Quaternion().fromAngleAxis(heading, Vector3f.UNIT_Y));
    }

    /** Keeps the hero out of buildings, trees, lamp posts and the parked car. */
    private void collideHero() {
        int ci = (int) Math.floor(px / P);
        int cj = (int) Math.floor(pz / P);
        float rad = 0.45f;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = ci - 1; i <= ci + 1; i++) {
                for (int j = cj - 1; j <= cj + 1; j++) {
                    Cell cell = cells.get(cellKey(i, j));
                    if (cell == null) {
                        continue;
                    }
                    for (float[] r : cell.rects) {
                        float nx = FastMath.clamp(px, r[0], r[1]);
                        float nz = FastMath.clamp(pz, r[2], r[3]);
                        float dx = px - nx;
                        float dz = pz - nz;
                        float d2 = dx * dx + dz * dz;
                        if (d2 >= rad * rad) {
                            continue;
                        }
                        if (d2 > 0.0001f) {
                            float d = (float) Math.sqrt(d2);
                            px += dx / d * (rad - d);
                            pz += dz / d * (rad - d);
                        } else {
                            float l = px - r[0];
                            float rr = r[1] - px;
                            float t = pz - r[2];
                            float b = r[3] - pz;
                            float m = Math.min(Math.min(l, rr), Math.min(t, b));
                            if (m == l) {
                                px -= l + rad;
                            } else if (m == rr) {
                                px += rr + rad;
                            } else if (m == t) {
                                pz -= t + rad;
                            } else {
                                pz += b + rad;
                            }
                        }
                    }
                }
            }
        }
        float dx = px - carX;
        float dz = pz - carZ;
        float d2 = dx * dx + dz * dz;
        float cr = 1.9f;
        if (d2 < cr * cr && d2 > 0.0001f) {
            float d = (float) Math.sqrt(d2);
            px += dx / d * (cr - d);
            pz += dz / d * (cr - d);
        }
    }

    /** A moving traffic car runs into the hero. */
    private void heroVsCar(Car c) {
        if (heroDead || heroHitCooldown > 0f || c.speed < 3f) {
            return;
        }
        float dx = px - c.wx;
        float dz = pz - c.wz;
        float fx = FastMath.sin(c.alpha);
        float fz = FastMath.cos(c.alpha);
        float rx = fz;
        float rz = -fx;
        float lx = dx * rx + dz * rz;
        float lz = dx * fx + dz * fz;
        if (Math.abs(lx) < 1.5f && Math.abs(lz) < 2.7f) {
            heroHitCooldown = 0.8f;
            heroKnock = 0.4f;
            float side = lx >= 0f ? 1f : -1f;
            heroVx = fx * c.speed * 0.6f + rx * side * 4f;
            heroVz = fz * c.speed * 0.6f + rz * side * 4f;
            damageHero(18f + c.speed * 2.2f);
            showMessage("OUCH!", ColorRGBA.Red, 0.8f);
        }
    }

    // ----------------------------------------------------------------- shooting
    private float rayBox(float ox, float oz, float dx, float dz,
                         float minX, float maxX, float minZ, float maxZ, float range) {
        float tmin = 0f;
        float tmax = range;
        if (Math.abs(dx) < 1e-6f) {
            if (ox < minX || ox > maxX) {
                return -1f;
            }
        } else {
            float t1 = (minX - ox) / dx;
            float t2 = (maxX - ox) / dx;
            if (t1 > t2) {
                float tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) {
                return -1f;
            }
        }
        if (Math.abs(dz) < 1e-6f) {
            if (oz < minZ || oz > maxZ) {
                return -1f;
            }
        } else {
            float t1 = (minZ - oz) / dz;
            float t2 = (maxZ - oz) / dz;
            if (t1 > t2) {
                float tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) {
                return -1f;
            }
        }
        return tmin;
    }

    private float rayCarBox(float ox, float oz, float dx, float dz, float cx, float cz, float alpha, float range) {
        float fx = FastMath.sin(alpha);
        float fz = FastMath.cos(alpha);
        float rx = fz;
        float rz = -fx;
        float lox = (ox - cx) * rx + (oz - cz) * rz;
        float loz = (ox - cx) * fx + (oz - cz) * fz;
        float ldx = dx * rx + dz * rz;
        float ldz = dx * fx + dz * fz;
        return rayBox(lox, loz, ldx, ldz, -1.0f, 1.0f, -2.1f, 2.1f, range);
    }

    /** 2D hitscan against buildings/props, traffic cars, the parked car and pedestrians. */
    private Hit raycast(float ox, float oz, float dx, float dz, float range) {
        Hit best = new Hit();
        best.t = range;
        int ci = (int) Math.floor(ox / P);
        int cj = (int) Math.floor(oz / P);
        int span = (int) Math.ceil(range / P) + 1;
        span = Math.min(span, 3);
        for (int i = ci - span; i <= ci + span; i++) {
            for (int j = cj - span; j <= cj + span; j++) {
                Cell cell = cells.get(cellKey(i, j));
                if (cell == null) {
                    continue;
                }
                for (float[] r : cell.rects) {
                    float t = rayBox(ox, oz, dx, dz, r[0], r[1], r[2], r[3], best.t);
                    if (t >= 0f && t < best.t) {
                        best.t = t;
                        best.type = 3;
                    }
                }
            }
        }
        for (Car c : cars) {
            float ddx = c.wx - ox;
            float ddz = c.wz - oz;
            if (ddx * ddx + ddz * ddz > (range + 4f) * (range + 4f)) {
                continue;
            }
            float t = rayCarBox(ox, oz, dx, dz, c.wx, c.wz, c.alpha, best.t);
            if (t >= 0f && t < best.t) {
                best.t = t;
                best.type = 2;
                best.car = c;
            }
        }
        if (onFoot) {
            float t = rayCarBox(ox, oz, dx, dz, carX, carZ, carHeading, best.t);
            if (t >= 0f && t < best.t) {
                best.t = t;
                best.type = 4;
            }
        }
        for (Ped p : peds) {
            if (p.hit || p.node.getCullHint() == Spatial.CullHint.Always) {
                continue;
            }
            Vector3f pp = p.node.getLocalTranslation();
            float cx = pp.x - ox;
            float cz = pp.z - oz;
            float tca = cx * dx + cz * dz;
            if (tca < 0f || tca > best.t) {
                continue;
            }
            float d2 = cx * cx + cz * cz - tca * tca;
            float rr = 0.5f;
            if (d2 < rr * rr) {
                float t = tca - (float) Math.sqrt(rr * rr - d2);
                if (t >= 0f && t < best.t) {
                    best.t = t;
                    best.type = 1;
                    best.ped = p;
                }
            }
        }
        return best;
    }

    private void addTracer(float x0, float y0, float z0, float x1, float y1, float z1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float flat = (float) Math.sqrt(dx * dx + dz * dz);
        float len = (float) Math.sqrt(flat * flat + dy * dy);
        if (len < 0.2f) {
            return;
        }
        Geometry g = new Geometry("Tracer", new Box(0.02f, 0.02f, len / 2f));
        g.setMaterial(tracerMat);
        g.setShadowMode(ShadowMode.Off);
        g.setLocalTranslation((x0 + x1) / 2f, (y0 + y1) / 2f, (z0 + z1) / 2f);
        float yaw = (float) Math.atan2(dx, dz);
        float pitch = -(float) Math.atan2(dy, flat);
        g.setLocalRotation(new Quaternion().fromAngles(pitch, yaw, 0f));
        rootNode.attachChild(g);
        Tracer t = new Tracer();
        t.s = g;
        t.life = 0.07f;
        tracers.add(t);
    }

    private void addSpark(float x, float y, float z) {
        Geometry g = glowBox("Spark", 0.12f, 0.12f, 0.12f, new Vector3f(x, y, z), tracerMat);
        rootNode.attachChild(g);
        Tracer t = new Tracer();
        t.s = g;
        t.life = 0.12f;
        tracers.add(t);
    }

    private void fireWeapon(Weapon w) {
        if (ammoMag[weaponIndex] <= 0) {
            reloadWeapon();
            return;
        }
        ammoMag[weaponIndex]--;
        fireCooldown = w.rate;
        muzzleTimer = 0.05f;
        muzzleFlash.setCullHint(Spatial.CullHint.Inherit);
        float fx = FastMath.sin(heading);
        float fz = FastMath.cos(heading);
        float rx = -fz;
        float rz = fx;
        float mx = px + fx * (0.3f + w.len) + rx * 0.29f;
        float mz = pz + fz * (0.3f + w.len) + rz * 0.29f;
        float my = 1.38f + 0.15f + heroY;
        if (w.rocket) {
            Rocket r = new Rocket();
            r.n = new Node("Rocket");
            r.n.attachChild(box("RocketBody", 0.07f, 0.07f, 0.35f, new Vector3f(0, 0, 0),
                    new ColorRGBA(0.3f, 0.36f, 0.2f, 1f)));
            r.n.attachChild(glowBox("RocketFlame", 0.05f, 0.05f, 0.18f, new Vector3f(0, 0, -0.5f),
                    unshaded(new ColorRGBA(1f, 0.6f, 0.15f, 1f))));
            r.n.setLocalTranslation(mx, my, mz);
            r.n.setLocalRotation(new Quaternion().fromAngleAxis(heading, Vector3f.UNIT_Y));
            r.x = mx;
            r.z = mz;
            r.dx = fx;
            r.dz = fz;
            rootNode.attachChild(r.n);
            rockets.add(r);
            shake = Math.max(shake, 0.35f);
        } else {
            for (int k = 0; k < w.pellets; k++) {
                float a = heading + (rnd.nextFloat() - 0.5f) * 2f * w.spread;
                float dx = FastMath.sin(a);
                float dz = FastMath.cos(a);
                Hit h = raycast(px, pz, dx, dz, w.range);
                float t = h.type == 0 ? w.range : h.t;
                float ex = px + dx * t;
                float ez = pz + dz * t;
                addTracer(mx, my, mz, ex, my - 0.15f, ez);
                if (h.type == 1) {
                    Vector3f pp = h.ped.node.getLocalTranslation();
                    launchPed(h.ped, pp.x, pp.z, dx, dz, w.knock);
                    addSpark(ex, my - 0.15f, ez);
                } else if (h.type == 2) {
                    dentNode(h.car.node, new Vector3f(ex, 0.8f, ez), new Vector3f(dx, 0f, dz),
                            0.12f + 0.02f * w.knock);
                    addSpark(ex, 0.8f, ez);
                    if (rnd.nextFloat() < 0.12f) {
                        spawnDebris(new Vector3f(ex, 0.8f, ez), new Vector3f(-dx, 0f, -dz), 1);
                    }
                } else if (h.type == 4) {
                    dentNode(playerCar, new Vector3f(ex, 0.8f, ez), new Vector3f(dx, 0f, dz), 0.12f);
                    playerDamage = Math.min(1f, playerDamage + 0.01f);
                    addSpark(ex, 0.8f, ez);
                } else if (h.type == 3) {
                    addSpark(ex, my - 0.15f, ez);
                }
            }
            shake = Math.max(shake, 0.04f + 0.02f * w.pellets);
        }
        if (ammoMag[weaponIndex] == 0) {
            reloadWeapon();
        }
    }

    /** Throws a pedestrian (bullet / explosion): the same tumble as being hit by the car. */
    private void launchPed(Ped p, float wx, float wz, float dx, float dz, float power) {
        p.hit = true;
        p.hLying = false;
        p.hTimer = 0f;
        p.hpx = wx;
        p.hpy = SIDEWALK_TOP + 0.4f;
        p.hpz = wz;
        p.hvx = dx * power + (rnd.nextFloat() - 0.5f) * 1.5f;
        p.hvz = dz * power + (rnd.nextFloat() - 0.5f) * 1.5f;
        p.hvy = 2.5f + power * 0.35f + rnd.nextFloat() * 1.5f;
        p.hax = 0f;
        p.hay = (float) Math.atan2(dx, dz);
        p.haz = 0f;
        p.hsx = (rnd.nextFloat() - 0.5f) * 12f;
        p.hsy = (rnd.nextFloat() - 0.5f) * 5f;
        p.hsz = (rnd.nextFloat() - 0.5f) * 12f;
        p.legL.setLocalRotation(new Quaternion().fromAngles((rnd.nextFloat() - 0.5f) * 2.4f, 0f, 0f));
        p.legR.setLocalRotation(new Quaternion().fromAngles((rnd.nextFloat() - 0.5f) * 2.4f, 0f, 0f));
        p.armL.setLocalRotation(new Quaternion().fromAngles((rnd.nextFloat() - 0.5f) * 3f, 0f, -1.2f));
        p.armR.setLocalRotation(new Quaternion().fromAngles((rnd.nextFloat() - 0.5f) * 3f, 0f, 1.2f));
        pedHits++;
        showMessage("-150", new ColorRGBA(1f, 0.6f, 0.1f, 1f), 0.8f);
    }

    private void explode(float x, float z) {
        Boom b = new Boom();
        b.g = new Geometry("Boom", new Sphere(10, 10, 1f));
        b.m = unshaded(new ColorRGBA(1f, 0.7f, 0.15f, 1f));
        b.g.setMaterial(b.m);
        b.g.setShadowMode(ShadowMode.Off);
        b.g.setLocalTranslation(x, 1.2f, z);
        rootNode.attachChild(b.g);
        booms.add(b);
        shake = Math.max(shake, 0.9f);

        for (Ped p : peds) {
            if (p.hit || p.node.getCullHint() == Spatial.CullHint.Always) {
                continue;
            }
            Vector3f pp = p.node.getLocalTranslation();
            float dx = pp.x - x;
            float dz = pp.z - z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d < 9f) {
                float inv = d < 0.01f ? 0f : 1f / d;
                launchPed(p, pp.x, pp.z, d < 0.01f ? 1f : dx * inv, d < 0.01f ? 0f : dz * inv,
                        4f + 14f * (1f - d / 9f));
            }
        }
        for (Car c : cars) {
            float dx = c.wx - x;
            float dz = c.wz - z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d < 10f) {
                dentNode(c.node, new Vector3f(x, 0.8f, z), new Vector3f(dx, 0f, dz), 0.3f + 0.7f * (1f - d / 10f));
                c.speed *= 0.2f;
            }
        }
        spawnDebris(new Vector3f(x, 0.9f, z), new Vector3f(rnd.nextFloat() - 0.5f, 0f, rnd.nextFloat() - 0.5f), 4);
        if (onFoot) {
            float dx = carX - x;
            float dz = carZ - z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d < 10f) {
                dentNode(playerCar, new Vector3f(x, 0.8f, z), new Vector3f(dx, 0f, dz), 0.8f * (1f - d / 10f) + 0.2f);
                addDamage(0.8f * (1f - d / 10f));
            }
            float hx = px - x;
            float hz = pz - z;
            float hd = (float) Math.sqrt(hx * hx + hz * hz);
            if (hd < 7f) {
                damageHero(10f + 60f * (1f - hd / 7f));
            }
        }
    }

    private void updateCombatFx(float tpf) {
        for (int i = tracers.size() - 1; i >= 0; i--) {
            Tracer t = tracers.get(i);
            t.life -= tpf;
            if (t.life <= 0f) {
                t.s.removeFromParent();
                tracers.remove(i);
            }
        }
        for (int i = rockets.size() - 1; i >= 0; i--) {
            Rocket r = rockets.get(i);
            float step = 48f * tpf;
            Hit h = raycast(r.x, r.z, r.dx, r.dz, step);
            if (h.type != 0) {
                float t = Math.max(0f, h.t - 0.3f);
                explode(r.x + r.dx * t, r.z + r.dz * t);
                r.n.removeFromParent();
                rockets.remove(i);
                continue;
            }
            r.x += r.dx * step;
            r.z += r.dz * step;
            r.dist += step;
            if (r.dist > 140f) {
                explode(r.x, r.z);
                r.n.removeFromParent();
                rockets.remove(i);
                continue;
            }
            r.n.setLocalTranslation(r.x, r.n.getLocalTranslation().y, r.z);
        }
        for (int i = booms.size() - 1; i >= 0; i--) {
            Boom b = booms.get(i);
            b.t += tpf;
            float k = b.t / 0.5f;
            if (k >= 1f) {
                b.g.removeFromParent();
                booms.remove(i);
                continue;
            }
            float s = 1f + 6f * k;
            b.g.setLocalScale(s, s * 0.8f, s);
            b.m.setColor("Color", new ColorRGBA(1f - 0.5f * k, 0.7f - 0.55f * k, 0.15f * (1f - k), 1f));
        }
    }

    // ================================================================= traffic lights
    /** axisZ true = traffic that moves along the Z axis. */
    private int lightState(int i, int j, boolean axisZ) {
        float t = (gameTime + i * 7.3f + j * 3.1f) % LIGHT_CYCLE;
        if (!axisZ) {
            t = (t + LIGHT_CYCLE / 2f) % LIGHT_CYCLE;
        }
        if (t < 9f) {
            return GREEN;
        }
        return t < 11f ? YELLOW : RED;
    }

    private void setLamps(Material[] mats, int st) {
        mats[0].setColor("Color", st == RED
                ? new ColorRGBA(1f, 0.1f, 0.05f, 1f) : new ColorRGBA(0.22f, 0f, 0f, 1f));
        mats[1].setColor("Color", st == YELLOW
                ? new ColorRGBA(1f, 0.75f, 0.05f, 1f) : new ColorRGBA(0.22f, 0.17f, 0f, 1f));
        mats[2].setColor("Color", st == GREEN
                ? new ColorRGBA(0.1f, 1f, 0.2f, 1f) : new ColorRGBA(0f, 0.18f, 0f, 1f));
    }

    private void updateSignals() {
        for (Cell c : cells.values()) {
            setLamps(c.matsZ, lightState(c.i, c.j, true));
            setLamps(c.matsX, lightState(c.i, c.j, false));
        }
    }

    private float signalTarget(float baseSpeed, float currentSpeed, int light, float distFront) {
        if (light == GREEN || distFront < -0.3f) {
            return baseSpeed;
        }
        if (light == YELLOW) {
            float need = currentSpeed * currentSpeed / (2f * Math.max(distFront, 0.1f));
            if (need > 10f) {
                return baseSpeed;
            }
        }
        if (distFront < 0.5f) {
            return 0f;
        }
        return Math.min(baseSpeed, FastMath.sqrt(2f * 9f * distFront));
    }

    // ================================================================= traffic
    private void buildTraffic() {
        for (int n = 0; n < TRAFFIC_COUNT; n++) {
            Car c = new Car();
            c.node = new Node("Traffic" + n);
            rootNode.attachChild(c.node);
            cars.add(c);
            respawn(c, true);
        }
    }

    private void respawn(Car c, boolean initial) {
        c.node.detachAllChildren();
        c.wheels.clear();
        String model = TRAFFIC_MODELS[rnd.nextInt(TRAFFIC_MODELS.length)];
        Spatial s = loadCar(model, c.wheels);
        if (s == null) {
            s = box("Fallback", 1f, 0.6f, 2f, new Vector3f(0, 0.7f, 0), ColorRGBA.Blue);
        }
        c.node.attachChild(s);
        c.lights = makeCarLights(null);
        c.node.attachChild(c.lights);

        c.base = 11f + rnd.nextFloat() * 10f;
        if (model.equals("truck") || model.equals("garbage-truck") || model.equals("firetruck")) {
            c.base *= 0.8f;
        }
        c.speed = c.base;
        c.turning = false;
        c.close = false;

        float minDist = initial ? 35f : 110f;
        float maxDist = initial ? 195f : 195f;
        for (int tries = 0; tries < 80; tries++) {
            float ang = rnd.nextFloat() * FastMath.TWO_PI;
            float rad = minDist + rnd.nextFloat() * (maxDist - minDist);
            float qx = px + FastMath.cos(ang) * rad;
            float qz = pz + FastMath.sin(ang) * rad;
            c.axis = rnd.nextInt(2);
            c.d = rnd.nextBoolean() ? 1 : -1;
            if (c.axis == 0) {
                c.k = Math.round(qz / P);
                c.s = qx;
            } else {
                c.k = Math.round(qx / P);
                c.s = qz;
            }
            float fromCenter = Math.abs(c.s - Math.round(c.s / P) * P);
            if (fromCenter < 11f) {
                continue;          // not on an intersection
            }
            syncCar(c);
            float dx = c.wx - px;
            float dz = c.wz - pz;
            float d2 = dx * dx + dz * dz;
            if (d2 < minDist * minDist || d2 > (TRAFFIC_RECYCLE_DIST - 15f) * (TRAFFIC_RECYCLE_DIST - 15f)) {
                continue;
            }
            if (laneOccupied(c)) {
                continue;
            }
            break;
        }
        syncCar(c);
    }

    private boolean laneOccupied(Car self) {
        for (Car o : cars) {
            if (o != self && !o.turning && o.axis == self.axis && o.k == self.k && o.d == self.d
                    && Math.abs(o.s - self.s) < 14f) {
                return true;
            }
        }
        return false;
    }

    /** Computes world position/heading of a car from its lane state and moves its node. */
    private void syncCar(Car c) {
        if (c.turning) {
            float u = c.t;
            float a = (1f - u) * (1f - u);
            float b = 2f * (1f - u) * u;
            float cc = u * u;
            c.wx = a * c.ex + b * c.cx + cc * c.tx;
            c.wz = a * c.ez + b * c.cz + cc * c.tz;
            float fx = 2f * (1f - u) * (c.cx - c.ex) + 2f * u * (c.tx - c.cx);
            float fz = 2f * (1f - u) * (c.cz - c.ez) + 2f * u * (c.tz - c.cz);
            c.alpha = (float) Math.atan2(fx, fz);
        } else if (c.axis == 0) {
            c.wx = c.s;
            c.wz = c.k * P + LANE * c.d;
            c.alpha = c.d > 0 ? FastMath.HALF_PI : -FastMath.HALF_PI;
        } else {
            c.wz = c.s;
            c.wx = c.k * P - LANE * c.d;
            c.alpha = c.d > 0 ? 0f : FastMath.PI;
        }
        c.node.setLocalTranslation(c.wx, 0, c.wz);
        c.node.setLocalRotation(new Quaternion().fromAngleAxis(c.alpha, Vector3f.UNIT_Y));
    }

    private void startRightTurn(Car c, int m) {
        c.turning = true;
        c.t = 0f;
        if (c.axis == 0) {
            float cx0 = m * P;
            float cz0 = c.k * P;
            float zin = cz0 + LANE * c.d;
            c.newAxis = 1;
            c.newD = c.d;
            c.newK = m;
            float xout = c.newK * P - LANE * c.newD;
            c.ex = cx0 - PAD_EDGE * c.d;
            c.ez = zin;
            c.cx = xout;
            c.cz = zin;
            c.tx = xout;
            c.tz = cz0 + PAD_EDGE * c.newD;
            c.newS = c.tz;
        } else {
            float cz0 = m * P;
            float cx0 = c.k * P;
            float xin = cx0 - LANE * c.d;
            c.newAxis = 0;
            c.newD = -c.d;
            c.newK = m;
            float zout = c.newK * P + LANE * c.newD;
            c.ex = xin;
            c.ez = cz0 - PAD_EDGE * c.d;
            c.cx = xin;
            c.cz = zout;
            c.tx = cx0 + PAD_EDGE * c.newD;
            c.tz = zout;
            c.newS = c.tx;
        }
        c.len = (float) (Math.hypot(c.cx - c.ex, c.cz - c.ez) + Math.hypot(c.tx - c.cx, c.tz - c.cz)) * 0.9f;
    }

    private void updateTraffic(float tpf) {
        boolean live = state == State.PLAYING;
        crashCooldown = Math.max(0f, crashCooldown - tpf);
        float fxp = FastMath.sin(heading);
        float fzp = FastMath.cos(heading);

        for (Car c : cars) {
            float target = c.base;

            if (c.turning) {
                target = Math.min(target, 8f);
            } else {
                // follow the car in front on the same lane
                for (Car o : cars) {
                    if (o == c || o.turning || o.axis != c.axis || o.k != c.k || o.d != c.d) {
                        continue;
                    }
                    float gap = (o.s - c.s) * c.d - 6.8f;
                    if (gap > -4f && gap < 16f) {
                        target = Math.min(target, o.speed * Math.min(1f, Math.max(0f, gap) / 16f));
                    }
                }
                // do not run over the player
                float lane = c.axis == 0 ? c.k * P + LANE * c.d : c.k * P - LANE * c.d;
                float lat = c.axis == 0 ? Math.abs(pz - lane) : Math.abs(px - lane);
                float lon = c.axis == 0 ? (px - c.s) * c.d : (pz - c.s) * c.d;
                if (lat < 2.6f && lon > 0f && lon < 24f) {
                    float pv = c.axis == 0 ? speed * fxp * c.d : speed * fzp * c.d;
                    float gap = lon - 6.8f;
                    target = Math.min(target, Math.max(0f, pv) * Math.min(1f, Math.max(0f, gap) / 16f));
                }
                if (onFoot) {            // the parked player car is an obstacle too
                    float lat2 = c.axis == 0 ? Math.abs(carZ - lane) : Math.abs(carX - lane);
                    float lon2 = c.axis == 0 ? (carX - c.s) * c.d : (carZ - c.s) * c.d;
                    if (lat2 < 2.6f && lon2 > 0f && lon2 < 24f) {
                        target = Math.min(target, 0f);
                    }
                }
                // traffic lights
                int m;
                float distFront;
                if (c.d > 0) {
                    m = (int) Math.ceil((c.s + STOP_DIST - 0.5f) / P);
                    distFront = (m * P - STOP_DIST - c.s) - 2.8f;
                } else {
                    m = (int) Math.floor((c.s - STOP_DIST + 0.5f) / P);
                    distFront = (c.s - (m * P + STOP_DIST)) - 2.8f;
                }
                int light = c.axis == 0 ? lightState(m, c.k, false) : lightState(c.k, m, true);
                target = Math.min(target, signalTarget(c.base, c.speed, light, distFront));
            }

            if (target < c.speed) {
                c.speed = Math.max(target, c.speed - 14f * tpf);
            } else {
                c.speed = Math.min(target, c.speed + 6f * tpf);
            }

            // move
            if (c.turning) {
                c.t += c.speed * tpf / Math.max(1f, c.len);
                if (c.t >= 1f) {
                    c.turning = false;
                    c.axis = c.newAxis;
                    c.d = c.newD;
                    c.k = c.newK;
                    c.s = c.newS;
                }
            } else {
                float prev = c.s;
                c.s += c.d * c.speed * tpf;
                // reaching an intersection pad: maybe turn right
                if (c.d > 0) {
                    int m = (int) Math.ceil((prev + PAD_EDGE) / P);
                    float entry = m * P - PAD_EDGE;
                    if (prev < entry && c.s >= entry && rnd.nextFloat() < 0.28f) {
                        startRightTurn(c, m);
                    }
                } else {
                    int m = (int) Math.floor((prev - PAD_EDGE) / P);
                    float entry = m * P + PAD_EDGE;
                    if (prev > entry && c.s <= entry && rnd.nextFloat() < 0.28f) {
                        startRightTurn(c, m);
                    }
                }
            }
            syncCar(c);

            c.spin += (c.speed * tpf) / (0.3f * CAR_SCALE);
            spinWheels(c.wheels, c.spin, 0f);

            float dx = c.wx - px;
            float dz = c.wz - pz;
            float dist2 = dx * dx + dz * dz;
            if (dist2 > TRAFFIC_RECYCLE_DIST * TRAFFIC_RECYCLE_DIST) {
                respawn(c, false);
                continue;
            }
            if (!live) {
                continue;
            }
            if (onFoot) {
                heroVsCar(c);
                continue;
            }
            collideWithCar(c, dist2);
        }
    }

    private void collideWithCar(Car c, float dist2) {
        if (dist2 > 36f) {
            // maybe finish a near miss
            if (c.close && dist2 > 49f) {
                c.close = false;
                if (speed > 20f) {
                    nearMisses++;
                    showMessage("NEAR MISS  +50", new ColorRGBA(0.3f, 0.9f, 1f, 1f), 0.9f);
                }
            }
            return;
        }
        float fpx = FastMath.sin(heading);
        float fpz = FastMath.cos(heading);
        float fcx = FastMath.sin(c.alpha);
        float fcz = FastMath.cos(c.alpha);
        boolean hit = false;
        for (int a = -1; a <= 1; a += 2) {
            for (int b = -1; b <= 1; b += 2) {
                float ax = px + fpx * 1.2f * a;
                float az = pz + fpz * 1.2f * a;
                float bx = c.wx + fcx * 1.3f * b;
                float bz = c.wz + fcz * 1.3f * b;
                float dx = ax - bx;
                float dz = az - bz;
                float d2 = dx * dx + dz * dz;
                if (d2 < 2.3f * 2.3f) {
                    hit = true;
                    float d = (float) Math.sqrt(Math.max(d2, 0.0001f));
                    float push = (2.3f - d) * 0.5f;
                    px += dx / d * push;
                    pz += dz / d * push;
                }
            }
        }
        if (hit) {
            if (crashCooldown <= 0f) {
                crashFx(c);
                registerCrash();
            }
            c.speed *= 0.6f;
            c.close = false;
        } else {
            c.close = true;
        }
    }

    // ================================================================= HUD / input / scores
    private BitmapText makeText(float sizeMul, ColorRGBA color) {
        BitmapText t = new BitmapText(guiFont);
        t.setSize(guiFont.getCharSet().getRenderedSize() * sizeMul);
        t.setColor(color);
        guiNode.attachChild(t);
        return t;
    }

    private void buildHud() {
        hud1 = makeText(1.5f, ColorRGBA.White);
        hud1.setLocalTranslation(20, cam.getHeight() - 14, 0);
        hud2 = makeText(1.1f, new ColorRGBA(1, 1, 1, 0.92f));
        hud2.setLocalTranslation(20, cam.getHeight() - 48, 0);
        hudMission = makeText(1.2f, new ColorRGBA(1f, 0.85f, 0.2f, 1f));
        hudHelp = makeText(1.0f, new ColorRGBA(1, 1, 1, 0.85f));
        hudHelp.setLocalTranslation(20, 34, 0);
        hudWeapon = makeText(1.3f, new ColorRGBA(1f, 0.9f, 0.4f, 1f));
        hudWeapon.setLocalTranslation(20, 70, 0);
        crosshair = new Node("Crosshair");
        ColorRGBA ch = new ColorRGBA(1f, 1f, 1f, 1f);
        float[][] bars = {{0, 14, 2, 10}, {0, -14, 2, 10}, {-14, 0, 10, 2}, {14, 0, 10, 2}};
        for (float[] b : bars) {
            Geometry q = guiQuad(crosshair, ch, 0f);
            q.setLocalScale(b[2], b[3], 1f);
            q.setLocalTranslation(b[0], b[1], 0f);
        }
        crosshair.setLocalTranslation(cam.getWidth() / 2f, cam.getHeight() / 2f, 0);
        crosshair.setCullHint(Spatial.CullHint.Always);
        guiNode.attachChild(crosshair);
        banner = makeText(3f, ColorRGBA.Red);

        menuLines = new BitmapText[12];
        for (int i = 0; i < menuLines.length; i++) {
            menuLines[i] = makeText(i == 0 ? 3.2f : 1.4f, ColorRGBA.White);
        }

        // arrow that points to the next beacon
        arrow = new Node("Arrow");
        Material am = unshaded(new ColorRGBA(1f, 0.85f, 0.1f, 1f));
        Geometry shaft = new Geometry("Shaft", new Box(3f, 22f, 0.5f));
        shaft.setMaterial(am);
        arrow.attachChild(shaft);
        Geometry head = new Geometry("Head", new Box(10f, 10f, 0.5f));
        head.setMaterial(am);
        head.setLocalTranslation(0, 26f, 0);
        head.setLocalRotation(new Quaternion().fromAngleAxis(FastMath.PI / 4f, Vector3f.UNIT_Z));
        arrow.attachChild(head);
        arrow.setLocalTranslation(cam.getWidth() / 2f, 150f, 0);
        guiNode.attachChild(arrow);

        buildMinimap();
        buildGauge();
    }

    private Geometry guiQuad(Node parent, ColorRGBA color, float z) {
        Geometry g = new Geometry("GuiQuad", new Box(0.5f, 0.5f, 0.05f));
        g.setMaterial(unshaded(color));
        g.setLocalTranslation(0, 0, z);
        parent.attachChild(g);
        return g;
    }

    private Node makeMarker(ColorRGBA color, float scale) {
        Node n = new Node("Marker");
        Material m = unshaded(color);
        Geometry body = new Geometry("MBody", new Box(2.6f, 5f, 0.3f));
        body.setMaterial(m);
        n.attachChild(body);
        Geometry nose = new Geometry("MNose", new Box(3.2f, 3.2f, 0.3f));
        nose.setMaterial(m);
        nose.setLocalTranslation(0, 6f, 0);
        nose.setLocalRotation(new Quaternion().fromAngleAxis(FastMath.PI / 4f, Vector3f.UNIT_Z));
        n.attachChild(nose);
        n.setLocalScale(scale);
        return n;
    }

    private void buildMinimap() {
        mapRoot = new Node("MiniMap");
        mapRoot.setLocalTranslation(cam.getWidth() - MAP_HALF - 24f, cam.getHeight() - MAP_HALF - 24f, 0);
        Geometry border = guiQuad(mapRoot, new ColorRGBA(0.9f, 0.9f, 0.9f, 1f), 0f);
        border.setLocalScale(MAP_HALF * 2f + 6f, MAP_HALF * 2f + 6f, 1f);
        Geometry bg = guiQuad(mapRoot, new ColorRGBA(0.10f, 0.13f, 0.12f, 1f), 1f);
        bg.setLocalScale(MAP_HALF * 2f, MAP_HALF * 2f, 1f);
        for (int i = 0; i < 16; i++) {
            Geometry q = guiQuad(mapRoot, new ColorRGBA(0.45f, 0.47f, 0.50f, 1f), 2f);
            mapRoadQuads.add(q);
        }
        for (int i = 0; i < TRAFFIC_COUNT; i++) {
            Geometry q = guiQuad(mapRoot, new ColorRGBA(0.95f, 0.30f, 0.25f, 1f), 3f);
            q.setLocalScale(4f, 4f, 1f);
            mapCarDots.add(q);
        }
        mapTargetMat = unshaded(new ColorRGBA(1f, 0.85f, 0.1f, 1f));
        mapTarget = new Geometry("MapTarget", new Box(4f, 4f, 0.05f));
        mapTarget.setMaterial(mapTargetMat);
        mapTarget.setLocalTranslation(0, 0, 4f);
        mapRoot.attachChild(mapTarget);
        mapPlayer = makeMarker(new ColorRGBA(0.3f, 1f, 0.45f, 1f), 1.0f);
        mapPlayer.setLocalTranslation(0, 0, 5f);
        mapRoot.attachChild(mapPlayer);
        guiNode.attachChild(mapRoot);
    }

    private void updateMinimap() {
        float sc = MAP_HALF / MAP_RANGE;
        int used = 0;
        float roadW = Math.max(3f, ROAD_HALF * 2f * sc);
        int i0 = (int) Math.floor((px - MAP_RANGE) / P);
        int i1 = (int) Math.ceil((px + MAP_RANGE) / P);
        for (int i = i0; i <= i1 && used < mapRoadQuads.size(); i++) {
            float sx = (i * P - px) * sc;
            if (Math.abs(sx) > MAP_HALF) {
                continue;
            }
            Geometry q = mapRoadQuads.get(used++);
            q.setCullHint(Spatial.CullHint.Inherit);
            q.setLocalScale(roadW, MAP_HALF * 2f, 1f);
            q.setLocalTranslation(sx, 0, 2f);
        }
        int j0 = (int) Math.floor((pz - MAP_RANGE) / P);
        int j1 = (int) Math.ceil((pz + MAP_RANGE) / P);
        for (int j = j0; j <= j1 && used < mapRoadQuads.size(); j++) {
            float sy = (pz - j * P) * sc;
            if (Math.abs(sy) > MAP_HALF) {
                continue;
            }
            Geometry q = mapRoadQuads.get(used++);
            q.setCullHint(Spatial.CullHint.Inherit);
            q.setLocalScale(MAP_HALF * 2f, roadW, 1f);
            q.setLocalTranslation(0, sy, 2f);
        }
        for (; used < mapRoadQuads.size(); used++) {
            mapRoadQuads.get(used).setCullHint(Spatial.CullHint.Always);
        }

        for (int n = 0; n < mapCarDots.size() && n < cars.size(); n++) {
            Car c = cars.get(n);
            float sx = (c.wx - px) * sc;
            float sy = (pz - c.wz) * sc;
            Geometry dot = mapCarDots.get(n);
            if (Math.abs(sx) < MAP_HALF - 3f && Math.abs(sy) < MAP_HALF - 3f) {
                dot.setCullHint(Spatial.CullHint.Inherit);
                dot.setLocalTranslation(sx, sy, 3f);
            } else {
                dot.setCullHint(Spatial.CullHint.Always);
            }
        }

        if (mission.goalCheckpoints > 0 && state == State.PLAYING) {
            float tx = FastMath.clamp((targetI * P - px) * sc, -MAP_HALF + 5f, MAP_HALF - 5f);
            float ty = FastMath.clamp((pz - targetJ * P) * sc, -MAP_HALF + 5f, MAP_HALF - 5f);
            mapTarget.setCullHint(Spatial.CullHint.Inherit);
            mapTarget.setLocalTranslation(tx, ty, 4f);
            float pulse = 0.65f + 0.35f * FastMath.sin(gameTime * 6f);
            mapTargetMat.setColor("Color", new ColorRGBA(pulse, 0.85f * pulse, 0.1f * pulse, 1f));
        } else {
            mapTarget.setCullHint(Spatial.CullHint.Always);
        }

        float fx = FastMath.sin(heading);
        float fz = FastMath.cos(heading);
        float ang = (float) Math.atan2(-fx, -fz);
        mapPlayer.setLocalRotation(new Quaternion().fromAngleAxis(ang, Vector3f.UNIT_Z));
    }

    private void buildGauge() {
        gaugeRoot = new Node("Gauge");
        float r = 78f;
        gaugeRoot.setLocalTranslation(cam.getWidth() - r - 40f, r + 34f, 0);
        Geometry plate = guiQuad(gaugeRoot, new ColorRGBA(0.05f, 0.06f, 0.07f, 1f), 0f);
        plate.setLocalScale(r * 2.1f, r * 2.1f, 1f);
        gaugeSegs = new Geometry[GAUGE_SEGMENTS];
        gaugeMats = new Material[GAUGE_SEGMENTS];
        for (int i = 0; i < GAUGE_SEGMENTS; i++) {
            float deg = 225f - 270f * i / (GAUGE_SEGMENTS - 1f);
            float rad = deg * FastMath.DEG_TO_RAD;
            gaugeMats[i] = unshaded(new ColorRGBA(0.15f, 0.15f, 0.17f, 1f));
            Geometry g = new Geometry("Seg", new Box(6f, 2.2f, 0.05f));
            g.setMaterial(gaugeMats[i]);
            g.setLocalTranslation(FastMath.cos(rad) * (r - 8f), FastMath.sin(rad) * (r - 8f), 1f);
            g.setLocalRotation(new Quaternion().fromAngleAxis(rad, Vector3f.UNIT_Z));
            gaugeRoot.attachChild(g);
            gaugeSegs[i] = g;
        }
        gaugeNeedle = new Node("Needle");
        Geometry nd = new Geometry("NeedleBar", new Box(r * 0.42f, 1.4f, 0.05f));
        nd.setMaterial(unshaded(new ColorRGBA(1f, 0.25f, 0.2f, 1f)));
        nd.setLocalTranslation(r * 0.42f, 0, 0);
        gaugeNeedle.attachChild(nd);
        gaugeNeedle.setLocalTranslation(0, 0, 3f);
        gaugeRoot.attachChild(gaugeNeedle);
        guiNode.attachChild(gaugeRoot);

        speedText = makeText(2.6f, ColorRGBA.White);
        speedText.setText("0");
    }

    private void updateGauge(int kmh) {
        float frac = FastMath.clamp(kmh / GAUGE_MAX_KMH, 0f, 1f);
        int lit = Math.round(frac * (GAUGE_SEGMENTS - 1)) + (kmh > 0 ? 1 : 0);
        if (lit != gaugeLit) {
            gaugeLit = lit;
            for (int i = 0; i < GAUGE_SEGMENTS; i++) {
                float f = i / (GAUGE_SEGMENTS - 1f);
                ColorRGBA c;
                if (i >= lit) {
                    c = new ColorRGBA(0.15f, 0.15f, 0.17f, 1f);
                } else if (f < 0.55f) {
                    c = new ColorRGBA(0.2f, 0.85f, 0.35f, 1f);
                } else if (f < 0.8f) {
                    c = new ColorRGBA(0.95f, 0.8f, 0.15f, 1f);
                } else {
                    c = new ColorRGBA(0.95f, 0.25f, 0.2f, 1f);
                }
                gaugeMats[i].setColor("Color", c);
            }
        }
        float deg = 225f - 270f * frac;
        gaugeNeedle.setLocalRotation(new Quaternion().fromAngleAxis(deg * FastMath.DEG_TO_RAD, Vector3f.UNIT_Z));
        Vector3f gp = gaugeRoot.getLocalTranslation();
        speedText.setLocalTranslation(gp.x - speedText.getLineWidth() / 2f, gp.y - 8f, 6f);
    }

    private void centerText(BitmapText t, String text, float y) {
        t.setText(text);
        t.setLocalTranslation((cam.getWidth() - t.getLineWidth()) / 2f, y, 0);
    }

    private void showMenuLines(boolean show) {
        for (BitmapText t : menuLines) {
            t.setCullHint(show ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
        }
    }

    private void setupInput() {
        inputManager.addMapping("Accelerate", new KeyTrigger(KeyInput.KEY_W), new KeyTrigger(KeyInput.KEY_UP));
        inputManager.addMapping("Brake", new KeyTrigger(KeyInput.KEY_S), new KeyTrigger(KeyInput.KEY_DOWN));
        inputManager.addMapping("Left", new KeyTrigger(KeyInput.KEY_A), new KeyTrigger(KeyInput.KEY_LEFT));
        inputManager.addMapping("Right", new KeyTrigger(KeyInput.KEY_D), new KeyTrigger(KeyInput.KEY_RIGHT));
        inputManager.addMapping("Nitro", new KeyTrigger(KeyInput.KEY_SPACE));
        inputManager.addMapping("Retry", new KeyTrigger(KeyInput.KEY_R));
        inputManager.addMapping("Menu", new KeyTrigger(KeyInput.KEY_M));
        inputManager.addMapping("Enter", new KeyTrigger(KeyInput.KEY_RETURN));
        inputManager.addMapping("EnterCar", new KeyTrigger(KeyInput.KEY_F));
        inputManager.addMapping("Fire", new MouseButtonTrigger(MouseInput.BUTTON_LEFT),
                new KeyTrigger(KeyInput.KEY_LCONTROL));
        inputManager.addMapping("Sprint", new KeyTrigger(KeyInput.KEY_LSHIFT), new KeyTrigger(KeyInput.KEY_RSHIFT));
        inputManager.addMapping("W1", new KeyTrigger(KeyInput.KEY_1));
        inputManager.addMapping("W2", new KeyTrigger(KeyInput.KEY_2));
        inputManager.addMapping("W3", new KeyTrigger(KeyInput.KEY_3));
        inputManager.addMapping("W4", new KeyTrigger(KeyInput.KEY_4));
        inputManager.addMapping("W5", new KeyTrigger(KeyInput.KEY_5));
        inputManager.addMapping("W6", new KeyTrigger(KeyInput.KEY_6));
        inputManager.addMapping("LookLeft", new MouseAxisTrigger(MouseInput.AXIS_X, true));
        inputManager.addMapping("LookRight", new MouseAxisTrigger(MouseInput.AXIS_X, false));
        inputManager.addMapping("TurnL", new KeyTrigger(KeyInput.KEY_Q));
        inputManager.addMapping("TurnR", new KeyTrigger(KeyInput.KEY_E));
        inputManager.setCursorVisible(false);
        inputManager.addListener(this, "Accelerate", "Brake", "Left", "Right", "Nitro",
                "Retry", "Menu", "Enter", "EnterCar", "Fire", "Sprint", "W1", "W2", "W3", "W4", "W5", "W6",
                "LookLeft", "LookRight", "TurnL", "TurnR");
    }

    private File scoreFile() {
        return new File(System.getProperty("user.home"), ".turboracer3d_scores.properties");
    }

    private void loadScores() {
        try {
            File f = scoreFile();
            if (f.exists()) {
                try (FileInputStream in = new FileInputStream(f)) {
                    scores.load(in);
                }
            }
        } catch (Exception e) {
            System.out.println("Could not load scores: " + e.getMessage());
        }
    }

    private void saveCarChoice() {
        scores.setProperty("car", Integer.toString(carIndex));
        try (FileOutputStream out = new FileOutputStream(scoreFile())) {
            scores.store(out, "Turbo Racer 3D best scores");
        } catch (Exception e) {
            System.out.println("Could not save car choice: " + e.getMessage());
        }
    }

    private long bestScore(int missionIndex) {
        try {
            return Long.parseLong(scores.getProperty("best" + missionIndex, "0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void saveBest(int missionIndex, long score) {
        if (score <= bestScore(missionIndex)) {
            return;
        }
        scores.setProperty("best" + missionIndex, Long.toString(score));
        try (FileOutputStream out = new FileOutputStream(scoreFile())) {
            scores.store(out, "Turbo Racer 3D best scores");
        } catch (Exception e) {
            System.out.println("Could not save scores: " + e.getMessage());
        }
    }

    // ================================================================= beacon
    private void buildBeacon() {
        beacon = new Node("Beacon");
        beaconMat = unshaded(new ColorRGBA(1f, 0.85f, 0.1f, 1f));
        beacon.attachChild(glowBox("Pillar", 0.7f, 22f, 0.7f, new Vector3f(0, 22f, 0), beaconMat));
        beacon.attachChild(glowBox("Base", 5f, 0.15f, 5f, new Vector3f(0, 0.2f, 0), beaconMat));
        rootNode.attachChild(beacon);
        beacon.setCullHint(Spatial.CullHint.Always);
    }

    private void chooseNextTarget() {
        int pi = Math.round(px / P);
        int pj = Math.round(pz / P);
        for (int tries = 0; tries < 50; tries++) {
            int di = rnd.nextInt(9) - 4;
            int dj = rnd.nextInt(9) - 4;
            int manhattan = Math.abs(di) + Math.abs(dj);
            if (manhattan >= 2 && manhattan <= 4 && !(di == 0 && dj == 0)) {
                targetI = pi + di;
                targetJ = pj + dj;
                break;
            }
        }
        beacon.setLocalTranslation(targetI * P, 0, targetJ * P);
        beacon.setCullHint(Spatial.CullHint.Inherit);
    }

    // ================================================================= states
    private void enterMenu() {
        leaveFootMode();
        state = State.MENU;
        speed = 0f;
        steering = 0f;
        dayPhase = MISSIONS[selectedMission].startPhase;
        showMenuLines(true);
        showDashboard(false);
        banner.setText("");
        beacon.setCullHint(Spatial.CullHint.Always);
        arrow.setCullHint(Spatial.CullHint.Always);
        refreshMenuText();
    }

    private String statBar(float value, float max) {
        int n = Math.max(1, Math.min(10, Math.round(value / max * 10f)));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            sb.append(i < n ? '#' : '.');
        }
        return sb.append(']').toString();
    }

    private void refreshMenuText() {
        float h = cam.getHeight();
        centerText(menuLines[0], "TURBO RACER 3D", h - 70f);
        centerText(menuLines[1], "Choose a mission  (W / S to select, ENTER to start)", h - 140f);
        for (int i = 0; i < MISSIONS.length; i++) {
            String prefix = i == selectedMission ? ">  " : "    ";
            centerText(menuLines[2 + i], prefix + MISSIONS[i].name + "     best: " + bestScore(i),
                    h - 185f - i * 34f);
            menuLines[2 + i].setColor(i == selectedMission
                    ? new ColorRGBA(1f, 0.85f, 0.2f, 1f) : ColorRGBA.White);
        }
        centerText(menuLines[6], MISSIONS[selectedMission].description, h - 345f);
        CarSpec cs = CAR_SPECS[carIndex];
        centerText(menuLines[7], "<  A    CAR: " + cs.name + "  (" + (carIndex + 1) + "/" + CAR_SPECS.length
                + ")    D  >", h - 395f);
        menuLines[7].setColor(new ColorRGBA(0.4f, 0.9f, 1f, 1f));
        centerText(menuLines[8], "SPEED " + statBar(cs.maxSpeed, 72f) + "   ACCEL " + statBar(cs.accel, 28f)
                + "   HANDLING " + statBar(cs.handling, 1.2f), h - 430f);
        centerText(menuLines[9], "Follow the yellow arrow / minimap to the glowing beacon. Obey the traffic lights!",
                h - 480f);
        centerText(menuLines[10], "W/UP gas   S/DOWN brake & reverse   A/D steer   SPACE nitro", h - 515f);
        centerText(menuLines[11], "R retry   M menu", h - 545f);
        hud1.setText("");
        hud2.setText("");
        hudMission.setText("");
        hudHelp.setText("");
    }

    private void showDashboard(boolean show) {
        Spatial.CullHint hint = show ? Spatial.CullHint.Inherit : Spatial.CullHint.Always;
        mapRoot.setCullHint(hint);
        gaugeRoot.setCullHint(hint);
        speedText.setCullHint(hint);
    }

    private void startMission(int index) {
        leaveFootMode();
        selectedMission = index;
        mission = MISSIONS[index];
        state = State.PLAYING;
        showMenuLines(false);
        showDashboard(true);
        saveCarChoice();
        missionComplete = false;

        placePlayerAtStart();
        playerDamage = 0f;
        pedHits = 0;
        setPlayerCarModel(carIndex);      // fresh, undented car
        clearDebris();
        odometer = 0f;
        timeLeft = mission.timeLimit;
        checkpoints = 0;
        nearMisses = 0;
        crashes = 0;
        redLights = 0;
        crashCooldown = 0f;
        nitroEnergy = 1f;
        dayPhase = mission.startPhase;
        message = "";
        messageTimer = 0f;
        lastPadI = Integer.MIN_VALUE;
        lastPadJ = Integer.MIN_VALUE;
        manageCells(10000);
        for (Car c : cars) {
            respawn(c, true);
        }
        if (mission.goalCheckpoints > 0) {
            chooseNextTarget();
            arrow.setCullHint(Spatial.CullHint.Inherit);
        } else {
            beacon.setCullHint(Spatial.CullHint.Always);
            arrow.setCullHint(Spatial.CullHint.Always);
        }
        applyDayNight();
    }

    private void finishMission(boolean success, String reason) {
        if (state != State.PLAYING) {
            return;
        }
        state = State.RESULT;
        missionComplete = success;
        resultReason = reason;
        long score = checkpoints * 200L + nearMisses * 50L - crashes * 100L - redLights * 75L - pedHits * 150L;
        if (success) {
            score += (long) (timeLeft * 5f);
        }
        finalScore = Math.max(0L, score);
        if (success) {
            saveBest(selectedMission, finalScore);
        }
        beacon.setCullHint(Spatial.CullHint.Always);
        arrow.setCullHint(Spatial.CullHint.Always);
    }

    private void showMessage(String text, ColorRGBA color, float seconds) {
        message = text;
        messageColor = color;
        messageTimer = seconds;
    }

    // ================================================================= update loop
    @Override
    public void simpleUpdate(float tpf) {
        try {
            updateGame(tpf);
        } catch (RuntimeException e) {
            // never close the game: write the error to crash.log and drop the custom character
            try {
                java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter("crash.log", true));
                e.printStackTrace(pw);
                pw.close();
            } catch (java.io.IOException io) {
                // ignore
            }
            e.printStackTrace();
            if (heroModel != null) {
                heroModel.removeFromParent();
                heroModel = null;
                heroAnim = null;
                heroBody.setCullHint(Spatial.CullHint.Inherit);
            }
        }
    }

    private void updateGame(float tpf) {
        tpf = Math.min(tpf, 0.05f);
        gameTime += tpf;
        dayPhase = (dayPhase + tpf / DAY_LENGTH) % 1f;

        applyDayNight();
        manageCells(2);
        updateSignals();
        updatePlayer(tpf);
        updateTraffic(tpf);
        updatePeds(tpf);
        updateDebris(tpf);
        updateCombatFx(tpf);
        updateGroundFollow();
        updateCamera(tpf);
        updateBeacon(tpf);

        if (state == State.PLAYING) {
            updateMission(tpf);
        }
        updateHud(tpf);
    }

    private void updateMission(float tpf) {
        if (mission.timeLimit > 0f) {
            timeLeft -= tpf;
        }
        if (mission.goalCheckpoints > 0) {
            float dx = targetI * P - px;
            float dz = targetJ * P - pz;
            if (dx * dx + dz * dz < 12f * 12f) {
                checkpoints++;
                timeLeft += mission.checkpointBonus;
                if (checkpoints >= mission.goalCheckpoints) {
                    finishMission(true, "All beacons reached!");
                    return;
                }
                showMessage("BEACON!  +" + (int) mission.checkpointBonus + " s",
                        new ColorRGBA(0.3f, 1f, 0.4f, 1f), 2f);
                chooseNextTarget();
            }
        }
        if (mission.timeLimit > 0f && timeLeft <= 0f) {
            timeLeft = 0f;
            finishMission(false, "Time is up!");
        } else if (crashes > mission.maxCrashes) {
            finishMission(false, "Too many crashes!");
        } else if (redLights > mission.maxRedLights) {
            finishMission(false, "Too many red lights!");
        }
    }

    private void updateBeacon(float tpf) {
        if (beacon.getCullHint() == Spatial.CullHint.Always) {
            return;
        }
        float pulse = 0.65f + 0.35f * FastMath.sin(gameTime * 4f);
        beaconMat.setColor("Color", new ColorRGBA(1f * pulse, 0.85f * pulse, 0.1f * pulse, 1f));
    }

    // ----------------------------------------------------------- day / night
    private void applyDayNight() {
        float elev = FastMath.sin(dayPhase * FastMath.TWO_PI);
        float d = FastMath.clamp((elev + 0.12f) / 0.4f, 0f, 1f);
        d = d * d * (3f - 2f * d);
        float glow = Math.max(0f, 1f - Math.abs(elev) / 0.28f);

        ColorRGBA dayC = new ColorRGBA(0.62f, 0.78f, 0.93f, 1f);
        ColorRGBA nightC = new ColorRGBA(0.02f, 0.035f, 0.09f, 1f);
        ColorRGBA glowC = new ColorRGBA(0.95f, 0.48f, 0.25f, 1f);
        ColorRGBA sky = new ColorRGBA().interpolateLocal(nightC, dayC, d);
        sky.interpolateLocal(glowC, glow * 0.55f);
        sky.a = 1f;
        viewPort.setBackgroundColor(sky);
        if (fog != null) {
            fog.setFogColor(sky);
        }

        float amb = 0.18f + 0.47f * d;
        ambient.setColor(new ColorRGBA(amb * (0.85f + 0.15f * (1f - glow)), amb * (0.9f + 0.1f * (1f - glow)),
                amb * (0.8f + 0.2f * (1f - glow) + 0.2f * (1f - d)), 1f));
        float sunI = 0.06f + 1.2f * d;
        sun.setColor(new ColorRGBA(sunI, sunI * (0.95f - 0.25f * glow), sunI * (0.86f - 0.45f * glow), 1f));
        sun.setDirection(new Vector3f(-0.5f, -(0.26f + 0.42f * d), -0.35f).normalizeLocal());
        shadows.setShadowIntensity(0.15f + 0.5f * d);

        float night = 1f - d;
        if (matLamp != null) {
            matLamp.setColor("Color", new ColorRGBA(0.5f + 0.55f * night, 0.5f + 0.4f * night,
                    0.45f + 0.15f * night, 1f));
        }
        if (matPool != null) {
            matPool.setColor("Color", new ColorRGBA(1f, 0.82f, 0.5f, 0.6f * night));
        }
        if (bloom != null) {
            bloom.setBloomIntensity(0.9f * night);
        }
        boolean on = night > 0.45f;
        if (on != lightsOn) {
            lightsOn = on;
            Spatial.CullHint hint = on ? Spatial.CullHint.Inherit : Spatial.CullHint.Always;
            playerLights.setCullHint(hint);
            for (Car c : cars) {
                if (c.lights != null) {
                    c.lights.setCullHint(hint);
                }
            }
        }
        float beam = on ? 2.6f * Math.min(1f, (night - 0.45f) / 0.25f) : 0f;
        headlight.setColor(new ColorRGBA(beam, beam * 0.97f, beam * 0.85f, 1f));
    }

    // ----------------------------------------------------------------- player
    private void updatePlayer(float tpf) {
        if (onFoot) {
            updateHero(tpf);
            syncPlayerNode();
            return;
        }
        boolean drive = state == State.PLAYING;
        boolean boosting = drive && nitro && nitroEnergy > 0.02f && speed > 5f;

        // speed > 0 = forward. S brakes, then reverses slowly.
        if (drive && accelerating) {
            speed += (speed < 0f ? 45f : accelRate) * tpf;
        }
        if (drive && braking) {
            speed -= (speed > 0f ? 42f : 12f) * tpf;
        }
        if (!drive) {
            speed -= Math.signum(speed) * Math.min(Math.abs(speed), 30f * tpf);
        } else if (!accelerating && !braking) {
            speed -= Math.signum(speed) * Math.min(Math.abs(speed), 7f * tpf);
        }
        if (boosting) {
            speed += (accelRate + 10f) * tpf;
            nitroEnergy = Math.max(0f, nitroEnergy - 0.28f * tpf);
        } else {
            nitroEnergy = Math.min(1f, nitroEnergy + 0.08f * tpf);
        }
        float dmgFactor = 1f - 0.35f * playerDamage;
        speed = FastMath.clamp(speed, -10f, (boosting ? nitroTop : maxSpeed) * dmgFactor);

        if (drive && left) {
            steering = Math.max(-1f, steering - 3.6f * tpf);
        } else if (drive && right) {
            steering = Math.min(1f, steering + 3.6f * tpf);
        } else {
            steering *= Math.max(0f, 1f - 8f * tpf);
        }

        // steering: yaw rate grows with speed up to a point, then eases off for stability
        float absSpeed = Math.abs(speed);
        float grip = Math.min(1f, absSpeed / 8f);
        float highSpeedEase = 1f - 0.55f * Math.min(1f, absSpeed / nitroTop);
        float yawRate = steering * 1.75f * handling * grip * highSpeedEase * Math.signum(speed == 0f ? 1f : speed);
        heading -= yawRate * tpf;     // steering right (positive) turns clockwise, i.e. decreases yaw

        float fx = FastMath.sin(heading);
        float fz = FastMath.cos(heading);
        float prevX = px;
        float prevZ = pz;
        px += fx * speed * tpf;
        pz += fz * speed * tpf;

        if (drive) {
            collideWithBuildings(tpf);
            odometer += (float) Math.hypot(px - prevX, pz - prevZ);
            checkRedLight(prevX, prevZ);
        }
        syncPlayerNode();

        wheelSpin += speed * tpf / (0.3f * CAR_SCALE);
        spinWheels(playerWheels, wheelSpin, -steering * 0.45f);

        boolean brakeOn = drive && braking;
        ColorRGBA tailColor = brakeOn ? new ColorRGBA(1f, 0.05f, 0.05f, 1f) : new ColorRGBA(0.6f, 0.02f, 0.02f, 1f);
        for (Material m : playerTail) {
            if (m != null) {
                m.setColor("Color", tailColor);
            }
        }

        headlight.setPosition(new Vector3f(px + fx * 2.3f, 0.9f, pz + fz * 2.3f));
        headlight.setDirection(new Vector3f(fx, -0.1f, fz).normalizeLocal());

        updateSmoke();
        float targetFov = 60f + (Math.max(0f, speed) / nitroTop) * 18f;
        cam.setFrustumPerspective(targetFov, (float) cam.getWidth() / cam.getHeight(), 0.5f, 420f);
    }

    private void collideWithBuildings(float tpf) {
        int ci = (int) Math.floor(px / P);
        int cj = (int) Math.floor(pz / P);
        float fx = FastMath.sin(heading);
        float fz = FastMath.cos(heading);
        boolean touched = false;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = ci - 1; i <= ci + 1; i++) {
                for (int j = cj - 1; j <= cj + 1; j++) {
                    Cell cell = cells.get(cellKey(i, j));
                    if (cell == null) {
                        continue;
                    }
                    for (float[] r : cell.rects) {
                        for (int a = -1; a <= 1; a += 2) {
                            float cx = px + fx * 1.2f * a;
                            float cz = pz + fz * 1.2f * a;
                            float nx = FastMath.clamp(cx, r[0], r[1]);
                            float nz = FastMath.clamp(cz, r[2], r[3]);
                            float dx = cx - nx;
                            float dz = cz - nz;
                            float d2 = dx * dx + dz * dz;
                            float rad = 1.1f;
                            if (d2 >= rad * rad) {
                                continue;
                            }
                            touched = true;
                            if (d2 > 0.0001f) {
                                float d = (float) Math.sqrt(d2);
                                px += dx / d * (rad - d);
                                pz += dz / d * (rad - d);
                            } else {
                                float l = cx - r[0];
                                float rr = r[1] - cx;
                                float t = cz - r[2];
                                float b = r[3] - cz;
                                float m = Math.min(Math.min(l, rr), Math.min(t, b));
                                if (m == l) {
                                    px -= l + rad;
                                } else if (m == rr) {
                                    px += rr + rad;
                                } else if (m == t) {
                                    pz -= t + rad;
                                } else {
                                    pz += b + rad;
                                }
                            }
                        }
                    }
                }
            }
        }
        if (touched) {
            if (Math.abs(speed) > 8f && crashCooldown <= 0f) {
                wallFx();
                registerCrash();
            }
            speed *= Math.max(0f, 1f - 3.5f * tpf);
        }
    }

    private void registerCrash() {
        crashes++;
        crashCooldown = 0.9f;
        shake = 0.45f;
        showMessage("CRASH!", ColorRGBA.Red, 1.1f);
        speed *= 0.35f;
    }

    private void checkRedLight(float prevX, float prevZ) {
        int m = Math.round(px / P);
        int n = Math.round(pz / P);
        boolean inside = Math.abs(px - m * P) < PAD_EDGE && Math.abs(pz - n * P) < PAD_EDGE;
        if (!inside) {
            lastPadI = Integer.MIN_VALUE;
            lastPadJ = Integer.MIN_VALUE;
            return;
        }
        if (m == lastPadI && n == lastPadJ) {
            return;
        }
        lastPadI = m;
        lastPadJ = n;
        // which way did we enter? use the direction of travel
        float fx = FastMath.sin(heading);
        float fz = FastMath.cos(heading);
        boolean movingZ = Math.abs(fz) > Math.abs(fx);
        if (speed > 3f && lightState(m, n, movingZ) == RED) {
            redLights++;
            showMessage("RED LIGHT!  -75", ColorRGBA.Red, 2f);
        }
    }

    private void spinWheels(List<Spatial> wheels, float spin, float steerYaw) {
        Quaternion spinQ = new Quaternion().fromAngleAxis(spin, Vector3f.UNIT_X);
        for (Spatial w : wheels) {
            if (w.getName() != null && w.getName().contains("front")) {
                Quaternion yaw = new Quaternion().fromAngleAxis(steerYaw, Vector3f.UNIT_Y);
                w.setLocalRotation(yaw.mult(spinQ));
            } else {
                w.setLocalRotation(spinQ);
            }
        }
    }

    private void updateGroundFollow() {
        ground.setLocalTranslation(px, -0.35f, pz);
    }

    private void updateCamera(float tpf) {
        // smoothly follow the heading so turns feel natural
        float diff = heading - camHeading;
        while (diff > FastMath.PI) {
            diff -= FastMath.TWO_PI;
        }
        while (diff < -FastMath.PI) {
            diff += FastMath.TWO_PI;
        }
        camHeading += diff * Math.min(1f, (onFoot ? 18f : 5f) * tpf);

        float cfx = FastMath.sin(camHeading);
        float cfz = FastMath.cos(camHeading);
        float back = onFoot ? 4.2f : 8.2f + (Math.max(0f, speed) / nitroTop) * 3.0f;
        Vector3f target = new Vector3f(px - cfx * back, onFoot ? 2.4f + heroY : 3.0f + Math.max(0f, speed) * 0.01f, pz - cfz * back);
        if (onFoot) {
            target.addLocal(-cfz * 0.8f, 0f, cfx * 0.8f);      // over-the-shoulder
        }
        if (shake > 0f) {
            shake = Math.max(0f, shake - tpf);
            target.addLocal((rnd.nextFloat() - 0.5f) * shake * 1.4f,
                    (rnd.nextFloat() - 0.5f) * shake * 1.0f,
                    (rnd.nextFloat() - 0.5f) * shake * 1.4f);
        }
        Vector3f cur = cam.getLocation();
        cur.interpolateLocal(target, Math.min(1f, 10f * tpf));
        cam.setLocation(cur);
        cam.lookAt(new Vector3f(px + cfx * (onFoot ? 25f : 14f), onFoot ? 1.7f : 1.5f, pz + cfz * (onFoot ? 25f : 14f)), Vector3f.UNIT_Y);
    }

    // ----------------------------------------------------------------- HUD
    private String clock(float seconds) {
        int s = Math.max(0, (int) Math.ceil(seconds));
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private void updateHud(float tpf) {
        messageTimer = Math.max(0f, messageTimer - tpf);

        if (state == State.MENU) {
            banner.setText("");
            return;
        }

        int kmh = (int) (Math.abs(speed) * 2.2f);
        int bars = Math.round(nitroEnergy * 10f);
        StringBuilder nitroBar = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            nitroBar.append(i < bars ? '|' : '.');
        }
        long score = checkpoints * 200L + nearMisses * 50L - crashes * 100L - redLights * 75L - pedHits * 150L;

        String timeText = mission.timeLimit > 0f ? "TIME " + clock(timeLeft) : "FREE ROAM";
        String goalText = mission.goalCheckpoints > 0
                ? "BEACONS " + checkpoints + "/" + mission.goalCheckpoints : "DIST " + (int) odometer + " m";
        hud1.setText(String.format("%s    %s", timeText, goalText));
        speedText.setText((speed < -0.5f ? "R " : "") + kmh);
        updateGauge(kmh);
        updateMinimap();
        hud2.setText(String.format("NITRO [%s]   NEAR MISS %d   CRASHES %d   RED LIGHTS %d   SCORE %d   DAMAGE %d%%",
                nitroBar, nearMisses, crashes, redLights, Math.max(0L, score), (int) (playerDamage * 100f)));
        hudMission.setLocalTranslation(0, 0, 0);
        centerText(hudMission, mission.name, 70f);
        if (onFoot) {
            hudHelp.setText("WASD move | MOUSE aim | LMB/CTRL fire | SHIFT run | SPACE jump | 1-6 weapon | R reload | F car");
            Weapon cw = WEAPONS[weaponIndex];
            hudWeapon.setText(String.format("%s   %d / %d   %s   HP %d", cw.name, ammoMag[weaponIndex],
                    ammoReserve[weaponIndex], reloadTimer > 0f ? "RELOADING" : "", (int) heroHealth));
        } else {
            hudHelp.setText("W/UP gas | S/DOWN brake+reverse | A/D steer | SPACE nitro | F get out | R retry | M menu");
            hudWeapon.setText("");
        }
        gaugeRoot.setCullHint(onFoot ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
        speedText.setCullHint(onFoot ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
        crosshair.setCullHint(onFoot && state == State.PLAYING && !heroDead
                ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);

        // arrow to target
        if (mission.goalCheckpoints > 0 && state == State.PLAYING) {
            float tx = targetI * P - px;
            float tz = targetJ * P - pz;
            float fx = FastMath.sin(heading);
            float fz = FastMath.cos(heading);
            float forward = tx * fx + tz * fz;
            float rightC = tx * (-fz) + tz * fx;
            float rel = (float) Math.atan2(rightC, forward);
            arrow.setLocalRotation(new Quaternion().fromAngleAxis(-rel, Vector3f.UNIT_Z));
            int dist = (int) Math.hypot(tx, tz);
            hud2.setText(hud2.getText() + "   TARGET " + dist + " m");
        }

        if (state == State.RESULT) {
            banner.setColor(missionComplete ? new ColorRGBA(0.3f, 1f, 0.4f, 1f) : ColorRGBA.Red);
            centerText(banner, missionComplete ? "MISSION COMPLETE" : "MISSION FAILED", cam.getHeight() * 0.72f);
            String nextText = missionComplete && selectedMission < MISSIONS.length - 1 ? "next mission" : "retry";
            centerText(hudMission, resultReason + "   Score: " + finalScore + "   Best: " + bestScore(selectedMission)
                    + "      ENTER = " + nextText + "   R = retry   M = menu", cam.getHeight() * 0.72f - 60f);
        } else if (messageTimer > 0f) {
            banner.setColor(messageColor);
            centerText(banner, message, cam.getHeight() * 0.66f);
        } else {
            banner.setText("");
        }
    }

    // ----------------------------------------------------------------- input
    @Override
    public void onAnalog(String name, float value, float tpf) {
        if (!onFoot || heroDead || state != State.PLAYING) {
            return;
        }
        switch (name) {
            case "LookLeft": heading += value * 1.4f; break;
            case "LookRight": heading -= value * 1.4f; break;
            case "TurnL": heading += value * 2.4f; break;
            case "TurnR": heading -= value * 2.4f; break;
            default: break;
        }
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        switch (name) {
            case "Accelerate":
                accelerating = isPressed;
                if (isPressed && state == State.MENU) {
                    selectedMission = (selectedMission + MISSIONS.length - 1) % MISSIONS.length;
                    dayPhase = MISSIONS[selectedMission].startPhase;
                    refreshMenuText();
                }
                break;
            case "Brake":
                braking = isPressed;
                if (isPressed && state == State.MENU) {
                    selectedMission = (selectedMission + 1) % MISSIONS.length;
                    dayPhase = MISSIONS[selectedMission].startPhase;
                    refreshMenuText();
                }
                break;
            case "Left":
                left = isPressed;
                if (isPressed && state == State.MENU) {
                    setPlayerCarModel(carIndex - 1);
                    refreshMenuText();
                }
                break;
            case "Right":
                right = isPressed;
                if (isPressed && state == State.MENU) {
                    setPlayerCarModel(carIndex + 1);
                    refreshMenuText();
                }
                break;
            case "Nitro":
                nitro = isPressed;
                if (isPressed && onFoot) {
                    jumpRequested = true;
                }
                break;
            case "EnterCar":
                if (isPressed) {
                    toggleVehicle();
                }
                break;
            case "Fire":
                fireHeld = isPressed;
                fireEdge = isPressed;
                break;
            case "Sprint":
                sprint = isPressed;
                break;
            case "W1":
            case "W2":
            case "W3":
            case "W4":
            case "W5":
            case "W6":
                if (isPressed && onFoot && !heroDead) {
                    selectWeapon(name.charAt(1) - '1');
                }
                break;
            case "Retry":
                if (isPressed && onFoot && state == State.PLAYING) {
                    reloadWeapon();
                } else if (isPressed && state != State.MENU) {
                    startMission(selectedMission);
                }
                break;
            case "Menu":
                if (isPressed && state != State.MENU) {
                    enterMenu();
                }
                break;
            case "Enter":
                if (!isPressed) {
                    break;
                }
                if (state == State.MENU) {
                    startMission(selectedMission);
                } else if (state == State.RESULT) {
                    if (missionComplete && selectedMission < MISSIONS.length - 1) {
                        startMission(selectedMission + 1);
                    } else {
                        startMission(selectedMission);
                    }
                }
                break;
            default:
                break;
        }
    }
}