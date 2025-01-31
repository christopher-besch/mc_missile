package com.chrisbesch.mcmissile.guidance;

import com.chrisbesch.mcmissile.guidance.GuidanceGrpc.GuidanceBlockingStub;

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
    private Map<Integer, GuidanceBlockingStub> blockingStubs = new HashMap<Integer, GuidanceBlockingStub>();

    private GuidanceStubManager() {}

    public static GuidanceStubManager getInstance() {
        if (instance == null) {
            instance = new GuidanceStubManager();
        }
        return instance;
    }

    // throws StatusRuntimeException
    public MissileHardwareConfig registerMissile(Missile missile) {
        GuidanceBlockingStub blockingStub = getBlockingStub(missile.getConnectionId());
        return blockingStub.withDeadlineAfter(REGISTER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).registerMissile(missile);
    }

    // throws StatusRuntimeException
    public ControlInput getGuidance(MissileState missileState) {
        GuidanceBlockingStub blockingStub = getBlockingStub(missileState.getConnectionId());
        return blockingStub.withDeadlineAfter(GUIDANCE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).getGuidance(missileState);
    }

    // throws StatusRuntimeException
    private GuidanceBlockingStub getBlockingStub(int connectionId) {
        LOGGER.info("{}", this.blockingStubs);
        if (this.blockingStubs.get(connectionId) != null) {
            LOGGER.info("reusing stub");
            return this.blockingStubs.get(connectionId);
        }
        LOGGER.info("creating new stub");
        ManagedChannel channel = Grpc.newChannelBuilder(getServerAddress(connectionId), InsecureChannelCredentials.create()).build();
        // TODO: catch exception
        GuidanceBlockingStub blockingStub = GuidanceGrpc.newBlockingStub(channel);
        this.blockingStubs.put(connectionId, blockingStub);
        LOGGER.info("{}", this.blockingStubs);
        return blockingStub;
    }

    private static String getServerAddress(int connectionId) {
        if (LOCALHOST_GUIDANCE_CONTROL) {
            return "127.0.0.1:42069";
        }
        return GUIDANCE_CONTROL_ADDRESS_PREFIX + connectionId + ":42069";
    }
}
