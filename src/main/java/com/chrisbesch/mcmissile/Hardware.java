package com.chrisbesch.mcmissile;

import com.chrisbesch.mcmissile.guidance.MissileHardwareConfig;

import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;

public class Hardware {
    private static final String MOD_ID = "mc-missile";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // needs to be divisable by 3
    private static final int PAPER_VALUE = 10 * 9;
    // needs to be divisable by 9
    private static final int GUNPOWDER_VALUE = 3 * 9;
    // needs to be divisable by 3
    private static final int DYE_VALUE = 3 * 9;
    // needs to be divisable by 9
    private static final int BLAZE_POWDER_VALUE = 100 * 9;
    // needs to be divisable by 9
    private static final int COAL_VALUE = 20 * 9;
    // needs to be divisable by 3
    private static final int GOLD_NUGGET_VALUE = 3 * 9;
    // needs to be divisable by 3
    private static final int HEAD_VALUE = 10000 * 9;
    // needs to be divisable by 3
    private static final int FEATHER_VALUE = 20 * 9;
    // needs to be divisable by 3
    private static final int DIAMOND_VALUE = 1000 * 9;
    // needs to be divisable by 3
    private static final int GLOWSTONE_DUST_VALUE = 10 * 9;

    // There is no way to build a missile with a smaller budget.
    private static final int MIN_BUDGET =
            PAPER_VALUE * 1 / 3
                    + GUNPOWDER_VALUE * 1 / 3
                    + GUNPOWDER_VALUE * 1 / 3
                    + DYE_VALUE * 1 / 3;

    public int cost = 0;

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
        assert this.cost <= MIN_BUDGET;
    }

    public Hardware(MissileHardwareConfig hardwareConfig) {
        switch (hardwareConfig.getWarhead()) {
            case BLANK:
                this.cost += 0;
                this.shouldDetonate = false;
                break;
            case TNT_M:
                this.cost += 500;
                this.shouldDetonate = true;
                this.detonationPower = 6.0F;
                break;
            default:
                LOGGER.error("unknown warhead");
                return;
        }
        switch (hardwareConfig.getAirframe()) {
            case DEFAULT_AIRFRAME:
                this.cost += 50;
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
                this.cost += 50;
                this.accelerationCurve =
                        (Integer n) -> {
                            return n < 60 ? 0.4D : 0.0D;
                        };

                this.accelerationRelVariance = 0.01D;
                break;
            default:
                LOGGER.error("unknown motor");
                return;
        }
        switch (hardwareConfig.getBattery()) {
            case LI_ION_M:
                this.cost += 50;
                this.timeToLive = 200;
                break;
            default:
                LOGGER.error("unknown battery");
                return;
        }
        switch (hardwareConfig.getSeeker()) {
            case NO_SEEKER:
                this.cost += 0;
                this.seekerHeadShouldTargetEntity = false;
                break;
            case IR_SEEKER_M:
                this.cost += 500;
                this.seekerHeadShouldTargetEntity = true;
                this.seekerHeadTargetPosVariance = 0.0D;
                this.seekerHeadTargetVelVariance = 0.0D;
                this.seekerHeadFOV = 20.0D;
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
                this.cost += 0;
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

    public static int calculateBudget(
            List<FireworkExplosionComponent> explosions, int flightDuration) {
        LOGGER.info("calculating budget");
        int budget = 0;
        budget += PAPER_VALUE * 1 / 3;
        budget += GUNPOWDER_VALUE * flightDuration / 3;
        for (var explosion : explosions) {
            switch (explosion.shape) {
                case FireworkExplosionComponent.Type.SMALL_BALL:
                    budget += GUNPOWDER_VALUE * 1 / 3;
                    budget += DYE_VALUE * 1 / 3;
                    break;
                case FireworkExplosionComponent.Type.LARGE_BALL:
                    // 1/3 in firework star + 1/9 in fire charge
                    budget += GUNPOWDER_VALUE * 4 / 9;
                    budget += DYE_VALUE * 1 / 3;
                    budget += BLAZE_POWDER_VALUE * 1 / 9;
                    budget += COAL_VALUE * 1 / 9;
                    break;
                case FireworkExplosionComponent.Type.STAR:
                    budget += GUNPOWDER_VALUE * 1 / 3;
                    budget += DYE_VALUE * 1 / 3;
                    budget += GOLD_NUGGET_VALUE * 1 / 3;
                    break;
                case FireworkExplosionComponent.Type.CREEPER:
                    budget += GUNPOWDER_VALUE * 1 / 3;
                    budget += DYE_VALUE * 1 / 3;
                    budget += HEAD_VALUE * 1 / 3;
                    break;
                case FireworkExplosionComponent.Type.BURST:
                    budget += GUNPOWDER_VALUE * 1 / 3;
                    budget += DYE_VALUE * 1 / 3;
                    budget += FEATHER_VALUE * 1 / 3;
                    break;
            }
            if (explosion.hasTrail) {
                budget += DIAMOND_VALUE * 1 / 3;
            }
            if (explosion.hasTwinkle) {
                budget += GLOWSTONE_DUST_VALUE * 1 / 3;
            }
            LOGGER.info("{} {} {}", explosion.shape, explosion.hasTrail, explosion.hasTwinkle);
        }
        LOGGER.info("{}", budget);
        return budget;
    }
}
