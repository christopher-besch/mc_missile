package com.chrisbesch.mcmissile.guidance;

import com.chrisbesch.mcmissile.guidance.GuidanceGrpc.GuidanceStub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import java.util.concurrent.TimeUnit;

import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Grpc;

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
    // the initial connection is always slower than 40ms
    // the connection needs to be established first
    static final int REGISTER_TIMEOUT_MILLIS = 500;
    static final int GUIDANCE_TIMEOUT_MILLIS = 20;

    private static final String MOD_ID = "mc-missile";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static GuidanceStubManager instance = null;

    // one stub for each guidance control server connection
    private Map<Integer, GuidanceStub> stubs = new HashMap<Integer, GuidanceStub>();

    private Map<Missile, ControlInput> latestControlInputs = new HashMap<Missile, ControlInput>();

    private GuidanceStubManager() {}

    public static GuidanceStubManager getInstance() {
        if (instance == null) {
            instance = new GuidanceStubManager();
        }
        return instance;
    }

    // TODO: does it?
    // throws StatusRuntimeException
    public void establishGuidanceConnection(MissileState missileState) {
        // TODO:
        this.sendMissileState(missileState);
    }

    public void endGuidanceConnection(MissileState missileState) {
        this.sendMissileState(missileState);
        // TODO:
    }

    public ControlInput getLatestGuidance(Missile missile) {
        ControlInput latestControlInputCopy = null;
        synchronized (this.latestControlInputs) {
            // TODO: what happens when empty
            latestControlInputCopy = this.latestControlInputs.get(missile);
        }
        return latestControlInputCopy;
    }

    // throws StatusRuntimeException
    public void sendMissileState(MissileState missileState) {
        // TODO:
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
