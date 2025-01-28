package com.chrisbesch.mcmissile.guidance;

import com.chrisbesch.mcmissile.guidance.GuidanceGrpc.GuidanceBlockingStub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;

import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.Grpc;

public /* singleton */ class GuidanceStubManager {
    private static GuidanceStubManager instance = null;

    // one stub for each guidance control server connection
    private HashMap<Integer, GuidanceBlockingStub> blockingStubs = new HashMap<Integer, GuidanceBlockingStub>();

    private static final String MOD_ID = "mc-missile";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private GuidanceStubManager() {}

    public static GuidanceStubManager getInstance() {
        if (instance == null) {
            instance = new GuidanceStubManager();
        }
        return instance;
    }

    public MissileHardwareConfig registerMissile(Missile missile) {
        GuidanceBlockingStub blockingStub = getBlockingStub(missile.getConnectionId());
        try {
            // TODO: timeout
            return blockingStub.registerMissile(missile);
        } catch (StatusRuntimeException e) {
            LOGGER.error("Failed to register missile via grpc");
            // TODO: do error handling with exceptions
            return null;
        }
    }

    public ControlInput GetGuidance(MissileState missileState) {
        GuidanceBlockingStub blockingStub = getBlockingStub(missileState.getConnectionId());
        try {
            // TODO: timeout
            return blockingStub.getGuidance(missileState);
        } catch (StatusRuntimeException e) {
            LOGGER.error("Failed to get missile guidance via grpc");
            return null;
        }
    }

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
        return "MinecraftGuidanceControl" + connectionId + ":42069";
    }
}
