package com.chrisbesch.mcmissile;

import com.chrisbesch.mcmissile.guidance.GuidanceStubManager;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Mc_missile implements ModInitializer {
    public static final String MOD_ID = "mc_missile";

    // This logger is used to write text to the console and the log file.
    // It is considered best practice to use your mod id as the logger's name.
    // That way, it's clear which mod wrote info, warnings, and errors.
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        LOGGER.info("mc_missile booting up");
        String connectionIdStrsRaw = System.getenv("MC_MISSILE_GUIDANCE_CONNECTION_IDS");
        if (connectionIdStrsRaw == null) {
            throw new java.lang.RuntimeException(
                    "MC_MISSILE_GUIDANCE_CONNECTION_IDS environment variable needs to be defined");
        }
        String[] connectionIdStrs = connectionIdStrsRaw.split(",");
        for (String connectionIdStr : connectionIdStrs) {
            GuidanceStubManager.getInstance().createStub(Integer.parseInt(connectionIdStr));
        }
    }
}
