package com.chrisbesch.mcmissile;

import com.chrisbesch.mcmissile.guidance.MissileHardwareConfig;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

public class Hardware {
    private static final String MOD_ID = "mc-missile";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public Integer timeToLive;

    public double drag;
    public Function<Integer, Double> accelerationCurve;
    // in degrees per tick
    // applied to both yaw and pitch input
    public float maxRotationInput;

    public double accelerationRelVariance;
    public float rotationVariance;

    public double posVariance;
    public double velVariance;
    public double headingVariance;

    public double seekerHeadTargetPosVariance;
    public double seekerHeadTargetVelVariance;

    public double seekerHeadFOV;
    public double seekerHeadRange;
    public boolean seekerHeadShouldTargetEntity;
    public TypeFilter<Entity, ?> sensorHeadEntityFilter;

    public boolean shouldDetonate;
    public float detonationPower;

    // default config
    public Hardware() {
        // it shall not be possible to construct a missile with a budged below the cost of the
        // default hardware
        this(
                MissileHardwareConfig.newBuilder()
                        .setWarhead(MissileHardwareConfig.Warhead.BLANK)
                        .setAirframe(MissileHardwareConfig.Airframe.DEFAULT_AIRFRAME)
                        .setMotor(MissileHardwareConfig.Motor.SINGLE_STAGE_M)
                        .setBattery(MissileHardwareConfig.Battery.LI_ION_M)
                        .setSeeker(MissileHardwareConfig.Seeker.NO_SEEKER)
                        .setInertialSystem(MissileHardwareConfig.InertialSystem.DEFAULT_IMU)
                        .build());
    }

    public Hardware(MissileHardwareConfig hardwareConfig) {
        switch (hardwareConfig.getWarhead()) {
            case BLANK:
                this.shouldDetonate = false;
                break;
            case TNT_M:
                this.shouldDetonate = true;
                this.detonationPower = 6.0F;
                break;
            default:
                LOGGER.error("unknown warhead");
                return;
        }
        switch (hardwareConfig.getAirframe()) {
            case DEFAULT_AIRFRAME:
                this.drag = 0.05D;
                this.maxRotationInput = 10.0F;
                this.rotationVariance = 8.0F;
                break;
            default:
                LOGGER.error("unknown airframe");
                return;
        }
        switch (hardwareConfig.getMotor()) {
            case SINGLE_STAGE_M:
                this.accelerationCurve =
                        (Integer n) -> {
                            return n < 90 ? 0.4D : 0.0D;
                        };

                this.accelerationRelVariance = 0.01D;
                break;
            default:
                LOGGER.error("unknown motor");
                return;
        }
        switch (hardwareConfig.getBattery()) {
            case LI_ION_M:
                this.timeToLive = 200;
                break;
            default:
                LOGGER.error("unknown battery");
                return;
        }
        switch (hardwareConfig.getSeeker()) {
            case NO_SEEKER:
                this.seekerHeadShouldTargetEntity = false;
                break;
            case IR_SEEKER_M:
                this.seekerHeadShouldTargetEntity = true;
                this.seekerHeadTargetPosVariance = 0.0D;
                this.seekerHeadTargetVelVariance = 0.0D;
                this.seekerHeadFOV = 1.0D;
                this.seekerHeadRange = 200.0D;
                var seekerEntityName = hardwareConfig.getSeekerEntityName();
                if (seekerEntityName == null || seekerEntityName == "") {
                    this.sensorHeadEntityFilter = TypeFilter.instanceOf(LivingEntity.class);
                } else {
                    this.sensorHeadEntityFilter =
                            (TypeFilter<Entity, ?>)
                                    Registries.ENTITY_TYPE.get(Identifier.of(seekerEntityName));
                }
                break;
            default:
                LOGGER.error("unknown seeker");
                return;
        }
        switch (hardwareConfig.getInertialSystem()) {
            case DEFAULT_IMU:
                this.posVariance = 0.0D;
                this.velVariance = 0.0D;
                this.headingVariance = 0.0D;
                break;
            default:
                LOGGER.error("unknown imu");
                return;
        }

        LOGGER.info("loaded missile hardware config");
    }

    public int calculateCost() {
        // TODO:
        return 0;
    }
}
