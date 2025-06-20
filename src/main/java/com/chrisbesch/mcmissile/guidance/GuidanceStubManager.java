package com.chrisbesch.mcmissile.guidance;

import com.chrisbesch.mcmissile.guidance.GuidanceGrpc.GuidanceStub;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// This singleton handles connections to the guidance and control server.
// These will be reused.
// The address of the server is a prefix appended with the player specified connection id.
// This is done for security reasons as letting players define the entire address is unsafe.
// All calls include the connection id so that the manager knows what server to connect to.
public /* singleton */ class GuidanceStubManager {
    // in seconds
    static final int HEALTH_CHECK_SCHEDULE = 30;

    private static final String MOD_ID = "mc-missile";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static GuidanceStubManager instance = null;

    // one stub for each guidance control server connection
    private Map<Integer, GuidanceStub> stubs = new ConcurrentHashMap<Integer, GuidanceStub>();

    // one entry for each flying missile
    // the control input that was received last
    private Map<Missile, ControlInput> latestControlInputs =
            new ConcurrentHashMap<Missile, ControlInput>();
    // the last id of the control input consumed by the mod
    private Map<Missile, Integer> latestConsumedControlInputIds =
            new ConcurrentHashMap<Missile, Integer>();
    // latched when the connection is finished and can be closed
    private Map<Missile, CountDownLatch> finishLatches =
            new ConcurrentHashMap<Missile, CountDownLatch>();
    // the stream the missile states are sent to
    private Map<Missile, StreamObserver<MissileState>> missileStateObservers =
            new ConcurrentHashMap<Missile, StreamObserver<MissileState>>();

    private final ScheduledExecutorService helperExecutor = Executors.newScheduledThreadPool(1);

    private GuidanceStubManager() {}

    public static GuidanceStubManager getInstance() {
        if (instance == null) {
            instance = new GuidanceStubManager();
        }
        return instance;
    }

    public void establishGuidanceConnection(MissileState initialMissileState) {
        int connectionId = initialMissileState.getMissile().getConnectionId();
        // async stub
        GuidanceStub stub = this.stubs.get(connectionId);
        if (stub == null) {
            LOGGER.warn("there is no stub with connectionId {}", connectionId);
            return;
        }
        this.finishLatches.put(initialMissileState.getMissile(), new CountDownLatch(1));

        StreamObserver<ControlInput> controlInputObserver =
                new StreamObserver<ControlInput>() {
                    @Override
                    public void onNext(ControlInput controlInput) {
                        LOGGER.info(
                                "{}: received control input id: {}",
                                initialMissileState.getMissile().getId(),
                                controlInput.getId());
                        GuidanceStubManager.getInstance()
                                .latestControlInputs
                                .put(initialMissileState.getMissile(), controlInput);
                    }

                    @Override
                    public void onError(Throwable t) {
                        LOGGER.error(
                                "{}: grpc error: {}",
                                initialMissileState.getMissile().getId(),
                                Status.fromThrowable(t));
                        GuidanceStubManager.getInstance()
                                .finishLatches
                                .get(initialMissileState.getMissile())
                                .countDown();
                    }

                    @Override
                    public void onCompleted() {
                        LOGGER.info(
                                "{}: completed grpc connection",
                                initialMissileState.getMissile().getId());
                        GuidanceStubManager.getInstance()
                                .finishLatches
                                .get(initialMissileState.getMissile())
                                .countDown();
                    }
                };
        this.missileStateObservers.put(
                initialMissileState.getMissile(), stub.getGuidance(controlInputObserver));

        this.sendMissileState(initialMissileState);
    }

    // When the guidance connection hasn't been created yet, this doesn't do anything.
    public void endGuidanceConnection(MissileState missileState) {
        assert missileState.getDestroyed();

        if (this.missileStateObservers.get(missileState.getMissile()) == null) {
            LOGGER.warn(
                    "{}: trying to end guidance connection that doesn't exist",
                    missileState.getMissile().getId());
            return;
        }

        this.sendMissileState(missileState);
        this.missileStateObservers.get(missileState.getMissile()).onCompleted();
        this.missileStateObservers.remove(missileState.getMissile());

        // do cleanup in background
        this.helperExecutor.submit(
                () -> {
                    try {
                        if (!this.finishLatches
                                .get(missileState.getMissile())
                                .await(1, TimeUnit.MINUTES)) {
                            LOGGER.warn(
                                    "{}: getGuidance grpc can not finish within 1 minute",
                                    missileState.getMissile().getId());
                        }
                    } catch (InterruptedException e) {
                        LOGGER.error(
                                "{}: interrupted while awaiting end of getGuidance grpc: {}",
                                missileState.getMissile().getId(),
                                e.getMessage());
                    }
                    this.latestControlInputs.remove(missileState.getMissile());
                    this.latestConsumedControlInputIds.remove(missileState.getMissile());
                    this.finishLatches.remove(missileState.getMissile());
                    LOGGER.info(
                            "{}: completed shutdown of getGuidance grpc in helper thread",
                            missileState.getMissile().getId());
                });
    }

    // return null when the server didn't send anything
    public ControlInput consumeLatestControlInput(Missile missile) {
        var consumingControlInput = this.latestControlInputs.get(missile);
        var consumingControlInputId =
                consumingControlInput == null ? -1 : consumingControlInput.getId();

        var latestConsumedControlInputId = this.latestConsumedControlInputIds.get(missile);
        if (latestConsumedControlInputId == null) {
            latestConsumedControlInputId = -1;
        }
        if (consumingControlInputId <= latestConsumedControlInputId) {
            LOGGER.warn(
                    "{}: consuming the same control input again, the guidance control server {} is"
                            + " lagging behind, latest consumed id {}, now consuming id {}",
                    missile.getId(),
                    missile.getConnectionId(),
                    latestConsumedControlInputId,
                    consumingControlInputId);
        }
        this.latestConsumedControlInputIds.put(missile, consumingControlInputId);
        return consumingControlInput;
    }

    public void sendMissileState(MissileState missileState) {
        // TODO: remove
        LOGGER.info(
                "{} {} {}",
                this.missileStateObservers.size(),
                this.finishLatches.size(),
                this.latestControlInputs.size());
        LOGGER.info(
                "time: {} pitch: {} yaw: {}",
                missileState.getTime(),
                missileState.getPitch(),
                missileState.getYaw());
        LOGGER.info("{}: sending missile state", missileState.getMissile().getId());
        try {
            this.missileStateObservers.get(missileState.getMissile()).onNext(missileState);
        } catch (RuntimeException e) {
            this.missileStateObservers.get(missileState.getMissile()).onError(e);
            LOGGER.error(
                    "{}: sendMissileState grpc error: {}",
                    missileState.getMissile().getId(),
                    e.getMessage());
        }
    }

    public void createStub(int connectionId) {
        assert this.stubs.get(connectionId) == null;
        LOGGER.info("creating new stub for server {}", connectionId);
        ManagedChannel channel =
                Grpc.newChannelBuilder(
                                getServerAddress(connectionId), InsecureChannelCredentials.create())
                        .keepAliveTime(500, TimeUnit.MILLISECONDS)
                        .keepAliveTimeout(250, TimeUnit.MILLISECONDS)
                        .idleTimeout(1, TimeUnit.MINUTES)
                        .build();
        GuidanceStub stub = GuidanceGrpc.newStub(channel);
        this.stubs.put(connectionId, stub);
        initializeHealthCheck(connectionId);
    }

    public boolean hasStub(int connectionId) {
        return this.stubs.get(connectionId) != null;
    }

    // The health check is needed because grpc is lazy and doesn't actually
    // establish a connection until it is absolutely needed. That means the the
    // first missile fired would have to wait roughly half a second until the
    // first control input arrives. That is way too slow.
    private void initializeHealthCheck(int connectionId) {
        this.helperExecutor.scheduleAtFixedRate(
                () -> {
                    var stub = this.stubs.get(connectionId);
                    assert stub != null;
                    HealthRequest healthRequest = HealthRequest.newBuilder().build();

                    StreamObserver<HealthResponse> healthResponseObserver =
                            new StreamObserver<HealthResponse>() {
                                @Override
                                public void onNext(HealthResponse controlInput) {
                                    LOGGER.info("health check received for stub {}", connectionId);
                                }

                                @Override
                                public void onError(Throwable t) {
                                    LOGGER.error("health check failed for stub {}", connectionId);
                                }

                                @Override
                                public void onCompleted() {
                                    LOGGER.info("health check completed for stub {}", connectionId);
                                }
                            };
                    stub.healthCheck(healthRequest, healthResponseObserver);
                },
                0,
                HEALTH_CHECK_SCHEDULE,
                TimeUnit.SECONDS);
    }

    private static String getServerAddress(int connectionId) {
        String portStr = System.getenv("MC_MISSILE_GUIDANCE_PORT");
        if (portStr == null) {
            throw new java.lang.RuntimeException(
                    "MC_MISSILE_GUIDANCE_PORT environment variable needs to be defined");
        }
        Integer port = Integer.parseInt(portStr);

        var useLocalhost = System.getenv("MC_MISSILE_LOCALHOST_GUIDANCE_CONTROL");
        if (useLocalhost != null && useLocalhost.equals("true")) {
            LOGGER.warn("using localhost guidance server");
            return "127.0.0.1:" + port;
        }
        String guidanceControlAddressPrefix =
                System.getenv("MC_MISSILE_GUIDANCE_CONTROL_ADDRESS_PREFIX");
        if (guidanceControlAddressPrefix == null) {
            throw new java.lang.RuntimeException(
                    "MC_MISSILE_GUIDANCE_CONTROL_ADDRESS_PREFIX environment variable needs to be"
                            + " defined");
        }
        LOGGER.info("using {} prefix for guidance server", guidanceControlAddressPrefix);
        return guidanceControlAddressPrefix + connectionId + ":" + port;
    }
}
