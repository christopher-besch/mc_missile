package com.chrisbesch.mcmissile.guidance;

import com.chrisbesch.mcmissile.guidance.GuidanceGrpc.GuidanceStub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import java.util.concurrent.TimeUnit;

import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Grpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;

// This singleton handles connections to the guidance and control server.
// These will be reused.
// The address of the server is a prefix appended with the player specified connection id.
// This is done for security reasons as letting players define the entire address is unsafe.
// All calls include the connection id so that the manager knows what server to connect to.
public /* singleton */ class GuidanceStubManager {
    // Prefix of the address used to connect to the guidance and control server.
    // Set this to something that isn't a prefix of any other address on your network.
    static final String GUIDANCE_CONTROL_ADDRESS_PREFIX = "MinecraftGuidanceControl";
    // set this to true to test with a local guidance and control server
    // TODO: localhost doesn't seem to work
    static final boolean LOCALHOST_GUIDANCE_CONTROL = true;

    private static final String MOD_ID = "mc-missile";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static GuidanceStubManager instance = null;

    // one stub for each guidance control server connection
    private Map<Integer, GuidanceStub> stubs = new ConcurrentHashMap<Integer, GuidanceStub>();

    private Map<Missile, ControlInput> latestControlInputs = new ConcurrentHashMap<Missile, ControlInput>();
    private Map<Missile, CountDownLatch> finishLatches = new ConcurrentHashMap<Missile, CountDownLatch>();
    private Map<Missile, StreamObserver<MissileState>> missileStateObservers = new ConcurrentHashMap<Missile, StreamObserver<MissileState>>();
    private Map<Missile, Integer> latestConsumedControlInputIds = new ConcurrentHashMap<Missile, Integer>();

    private GuidanceStubManager() {}

    public static GuidanceStubManager getInstance() {
        if (instance == null) {
            instance = new GuidanceStubManager();
        }
        return instance;
    }

    public void establishGuidanceConnection(MissileState initialMissileState) {
        GuidanceStub stub = getStub(initialMissileState.getMissile().getConnectionId());
        this.finishLatches.put(initialMissileState.getMissile(), new CountDownLatch(1));

        StreamObserver<ControlInput> controlInputObserver = new StreamObserver<ControlInput>() {
            @Override
            public void onNext(ControlInput controlInput) {
                LOGGER.info("received control input id: {}", controlInput.getId());
                GuidanceStubManager.getInstance().latestControlInputs.put(initialMissileState.getMissile(), controlInput);
            }

            @Override
            public void onError(Throwable t) {
                LOGGER.error("grpc error: {}", Status.fromThrowable(t));
                GuidanceStubManager.getInstance().finishLatches.get(initialMissileState.getMissile()).countDown();
            }

            @Override
            public void onCompleted() {
                LOGGER.info("completed grpc connection with {}", initialMissileState.getMissile());
                GuidanceStubManager.getInstance().finishLatches.get(initialMissileState.getMissile()).countDown();
            }
        };
        this.missileStateObservers.put(initialMissileState.getMissile(), stub.getGuidance(controlInputObserver));

        this.sendMissileState(initialMissileState);
    }

    public void endGuidanceConnection(MissileState missileState) {
        this.sendMissileState(missileState);
        this.missileStateObservers.get(missileState.getMissile()).onCompleted();
        // TODO: maybe on different thread
        try {
            if (!this.finishLatches.get(missileState.getMissile()).await(1, TimeUnit.MINUTES)) {
                LOGGER.warn("getGuidance grpc can not finish within 1 minute");
            }
        } catch (InterruptedException e) {
            LOGGER.error("failed to await end of getGuidance grpc: {}", e.getMessage());
        }
        this.latestControlInputs.remove(missileState.getMissile());
        this.finishLatches.remove(missileState.getMissile());
        this.missileStateObservers.remove(missileState.getMissile());
        this.latestConsumedControlInputIds.remove(missileState.getMissile());
    }

    // TODO: maybe rename
    public ControlInput consumeLatestControlInput(Missile missile) {
        var consumingControlInput = this.latestControlInputs.get(missile);
        var consumingControlInputId = consumingControlInput == null ? -1 : consumingControlInput.getId();
        var latestConsumedControlInputId = this.latestConsumedControlInputIds.get(missile);
        if (latestConsumedControlInputId == null) {
            latestConsumedControlInputId = -1;
        }
        if (consumingControlInputId <= latestConsumedControlInputId) {
            LOGGER.warn("consuming the same control input again, the guidance control server {} is lagging behind, latest consumed id {}, now consuming id {}", missile.getConnectionId(), latestConsumedControlInputId, consumingControlInputId);
        }
        this.latestConsumedControlInputIds.put(missile, consumingControlInputId);
        return consumingControlInput;
    }

    public void sendMissileState(MissileState missileState) {
        LOGGER.info("{} {} {}", this.missileStateObservers.size(), this.finishLatches.size(), this.latestControlInputs.size());
        LOGGER.info("sending missile state");
        try {
            this.missileStateObservers.get(missileState.getMissile()).onNext(missileState);
        } catch (RuntimeException e) {
            this.missileStateObservers.get(missileState.getMissile()).onError(e);
            LOGGER.error("sendMissileState grpc error: {}", e.getMessage());
        }
    }

    // throws StatusRuntimeException
    private GuidanceStub getStub(int connectionId) {
        LOGGER.info("{}", this.stubs);
        if (this.stubs.get(connectionId) != null) {
            LOGGER.info("reusing stub");
            return this.stubs.get(connectionId);
        }
        LOGGER.info("creating new stub");
        ManagedChannel channel = Grpc.newChannelBuilder(getServerAddress(connectionId), InsecureChannelCredentials.create())
            .keepAliveTime(500, TimeUnit.MILLISECONDS)
            .keepAliveTimeout(250, TimeUnit.MILLISECONDS)
            .idleTimeout(1, TimeUnit.MINUTES)
            .build();
        // TODO: catch exception
        GuidanceStub stub = GuidanceGrpc.newStub(channel);
        this.stubs.put(connectionId, stub);
        LOGGER.info("{}", this.stubs);
        return stub;
    }

    private static String getServerAddress(int connectionId) {
        if (LOCALHOST_GUIDANCE_CONTROL) {
            return "127.0.0.1:42069";
        }
        return GUIDANCE_CONTROL_ADDRESS_PREFIX + connectionId + ":42069";
    }
}
