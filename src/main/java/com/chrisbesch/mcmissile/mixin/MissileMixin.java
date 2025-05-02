package com.chrisbesch.mcmissile.mixin;

import com.chrisbesch.mcmissile.MissileDiscardedException;
import com.chrisbesch.mcmissile.guidance.ControlInput;
import com.chrisbesch.mcmissile.guidance.GuidanceStubManager;
import com.chrisbesch.mcmissile.guidance.Missile;
import com.chrisbesch.mcmissile.guidance.MissileHardwareConfig;
import com.chrisbesch.mcmissile.guidance.MissileState;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FlyingItemEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(FireworkRocketEntity.class)
public abstract class MissileMixin extends ProjectileEntity implements FlyingItemEntity {
    private static final String MOD_ID = "mc-missile";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Pattern MISSILE_NAME_PATTERN =
            Pattern.compile("^mc_missile/(\\d\\d)/(.+)$");

    private final Random random = Random.create();

    private int tickCount = 0;

    // set iff this is a missile
    private Missile missile;

    // flight parameters //
    private Vec3d gravity = new Vec3d(0.0D, -0.2D, 0.0D);

    private Integer timeToLive;

    // position is stored in ProjectileEntity
    // velocity is stored in ProjectileEntity
    // rotation is stored in ProjectileEntity
    private Double drag;
    private Function<Integer, Double> accelerationCurve;
    // in degrees per tick
    // applied to both yaw and pitch input
    private Float maxRotationInput;

    private Double accelerationRelVariance;
    private Double rotationVariance;

    private Double posVariance;
    private Double velVariance;
    private Double headingVariance;

    private Double seekerHeadTargetPosVariance;
    private Double seekerHeadTargetVelVariance;

    private Double seekerHeadFOV;
    private Double seekerHeadRange;
    private boolean seekerHeadShouldTargetEntity;
    private TypeFilter<Entity, ?> sensorHeadEntityFilter;
    private Entity seekerHeadEntityLock;

    private MissileHardwareConfig.Warhead warhead;

    // this constructor is only needed to make the compiler happy
    public MissileMixin(EntityType<? extends ProjectileEntity> entityType, World world) {
        super(entityType, world);
    }

    private void identifyMissile() {
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;

        // shotatangle when shot by crossbow or dispenser
        if (!thisObject.wasShotAtAngle()) {
            LOGGER.info("rocket wasn't fired at angle");
            return;
        }

        List<FireworkExplosionComponent> explosions = thisObject.getExplosions();
        if (explosions.size() == 0) {
            LOGGER.info("rocket doesn't have explosives");
            return;
        }

        Text customName = thisObject.getStack().get(DataComponentTypes.CUSTOM_NAME);
        if (customName == null) {
            LOGGER.info("rocket doesn't have a custom name");
            return;
        }

        Matcher matcher = MISSILE_NAME_PATTERN.matcher(customName.getString());
        if (!matcher.matches()) {
            LOGGER.info("rocket's name doesn't match missile requirement");
            return;
        }

        // now we know this is a missile
        var missileBuilder =
                Missile.newBuilder()
                        .setName(matcher.group(2))
                        // don't do negative numbers
                        .setId(Math.abs(this.random.nextInt()))
                        // TODO: set proper budget
                        // TODO: figuring out if the rocket has big balls isn't working
                        // var bigBallCount = explosions.stream().filter(e -> e.shape ==
                        // FireworkExplosionComponent.Type.BIG_BALL).count();
                        // LOGGER.info("{}", bigBallCount);
                        .setBudget(0);

        try {
            missileBuilder.setConnectionId(Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException e) {
            // this should never happen
            LOGGER.error("failed to convert connectionId in '{}'", customName.getString());
            return;
        }

        this.missile = missileBuilder.build();
        loadDefaultHardwareConfig();
        LOGGER.info(
                "detected missile {} on connection id {}, missile id {}",
                this.missile.getName(),
                this.missile.getConnectionId(),
                this.missile.getId());
    }

    private void launchMissile() {
        assert this.missile != null;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;

        LOGGER.info("missile launch");
        // Set the rotation to be parallel with the initial velocity vector.
        // This is the direction the rocket was shot.
        thisObject.updateRotation();
        if (!thisObject.isSilent()) {
            LOGGER.info("sound");
            thisObject
                    .getWorld()
                    .playSound(
                            null,
                            thisObject.getX(),
                            thisObject.getY(),
                            thisObject.getZ(),
                            SoundEvents.ENTITY_ENDER_DRAGON_SHOOT,
                            SoundCategory.AMBIENT,
                            20.0F,
                            1.0F);
        }

        // Add movement of the crossbow owner.
        // This doesn't change the orientation of the rocket.
        Entity owner = thisObject.getOwner();
        if (owner != null) {
            LOGGER.info("applying owner velocity {}", owner.getVelocity());
            thisObject.setVelocity(owner.getVelocity());
            // move once so that we don't bump into flying shooter
            thisObject.move(MovementType.SELF, owner.getVelocity().multiply(2.0D));
            thisObject.setVelocity(owner.getVelocity());
            thisObject.velocityDirty = true;
        } else {
            thisObject.setVelocity(Vec3d.ZERO);
            thisObject.velocityDirty = true;
        }
        detectEntities();
        GuidanceStubManager.getInstance().establishGuidanceConnection(constructMissileState());
    }

    private void readControlInput() throws MissileDiscardedException {
        assert this.missile != null;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;

        var controlInput =
                GuidanceStubManager.getInstance().consumeLatestControlInput(this.missile);
        if (controlInput != null) {
            // Only load the config directly after launch.
            // When there is no config given, use the default.
            if (this.tickCount == 1 && controlInput.getHardwareConfig() != null) {
                loadHardwareConfig(controlInput.getHardwareConfig(), false);
            }
            applyControlInput(controlInput);
        }
    }

    private void detectEntities() {
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;
        World world = thisObject.getWorld();
        if (!(thisObject.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }
        this.sensorHeadEntityFilter =
                (TypeFilter<Entity, Entity>) Registries.ENTITY_TYPE.get(Identifier.of("pig"));
        Vec3d pos = thisObject.getPos();
        // TODO: remove
        this.seekerHeadRange = 200.0D;
        this.seekerHeadFOV = 1.0D;
        List<? extends Entity> possible_targets =
                serverWorld.getEntitiesByType(
                        this.sensorHeadEntityFilter,
                        // search in a large box around the missile
                        new Box(
                                pos.getX() - this.seekerHeadRange,
                                pos.getY() - this.seekerHeadRange,
                                pos.getZ() - this.seekerHeadRange,
                                pos.getX() + this.seekerHeadRange,
                                pos.getY() + this.seekerHeadRange,
                                pos.getZ() + this.seekerHeadRange),
                        possible_target ->
                                (this.canSee((Entity) possible_target))
                                        // check the target is not out of range (i.e. in the corners
                                        // of the big box)
                                        && pos.subtract(possible_target.getPos()).lengthSquared()
                                                <= (this.seekerHeadRange * this.seekerHeadRange));
        this.seekerHeadEntityLock = minAngleTarget(possible_targets);
        if (this.seekerHeadEntityLock != null) {
            this.seekerHeadEntityLock.setGlowing(true);
        }
    }

    private Entity minAngleTarget(List<? extends Entity> targets) {
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;
        Vec3d missile_heading =
                thisObject
                        .getRotationVector(-thisObject.getPitch(), -thisObject.getYaw())
                        .normalize();
        Entity maxTarget = null;
        // only accept values that are not outside our field of view
        double maxDotProd = Math.cos(this.seekerHeadFOV);
        for (Entity entity : targets) {
            Vec3d dir_to_target = entity.getPos().subtract(thisObject.getPos()).normalize();
            double dotProd = missile_heading.dotProduct(dir_to_target);
            // TODO: remove
            LOGGER.info("{}", dotProd);
            // overwrite old data to include edge case
            if (dotProd >= maxDotProd) {
                maxDotProd = dotProd;
                maxTarget = entity;
            }
        }
        // TODO: remove
        LOGGER.info("{}", maxDotProd);
        return maxTarget;
    }

    private boolean canSee(Entity entity) {
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;
        if (entity.getWorld() != this.getWorld()) {
            return false;
        }
        return this.getWorld()
                        .raycast(
                                new RaycastContext(
                                        thisObject.getPos(),
                                        entity.getPos(),
                                        RaycastContext.ShapeType.COLLIDER,
                                        RaycastContext.FluidHandling.NONE,
                                        this))
                        .getType()
                == HitResult.Type.MISS;
    }

    private void loadDefaultHardwareConfig() {
        assert this.missile != null;
        // it shall not be possible to construct a missile with a budged below the cost of the
        // default hardware
        loadHardwareConfig(
                MissileHardwareConfig.newBuilder()
                        .setWarhead(MissileHardwareConfig.Warhead.TNT_M)
                        .setAirframe(MissileHardwareConfig.Airframe.DEFAULT_AIRFRAME)
                        .setMotor(MissileHardwareConfig.Motor.SINGLE_STAGE_M)
                        .setBattery(MissileHardwareConfig.Battery.LI_ION_M)
                        .setSeeker(MissileHardwareConfig.Seeker.NO_SEEKER)
                        .setInertialSystem(MissileHardwareConfig.InertialSystem.DEFAULT_IMU)
                        .build(),
                false);
    }

    private void loadHardwareConfig(MissileHardwareConfig hardwareConfig, boolean ignoreBudget) {
        assert this.missile != null;
        assert hardwareConfig != null;
        if (!ignoreBudget) {
            int cost = calculateCost(hardwareConfig);
            if (cost > this.missile.getBudget()) {
                LOGGER.warn(
                        "{}: missile is too expensive {}, budget only {}",
                        this.missile.getId(),
                        cost,
                        this.missile.getBudget());
                return;
            }
        }

        this.warhead = hardwareConfig.getWarhead();
        switch (hardwareConfig.getAirframe()) {
            case DEFAULT_AIRFRAME:
                this.drag = 0.05D;
                this.maxRotationInput = 10.0F;
                this.rotationVariance = 8.0D;
                break;
            default:
                LOGGER.error("{}: unknown airframe", this.missile.getId());
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
                LOGGER.error("{}: unknown motor", this.missile.getId());
                return;
        }
        switch (hardwareConfig.getBattery()) {
            case LI_ION_M:
                this.timeToLive = 200;
                break;
            default:
                LOGGER.error("{}: unknown battery", this.missile.getId());
                return;
        }
        switch (hardwareConfig.getSeeker()) {
            case NO_SEEKER:
                this.seekerHeadShouldTargetEntity = false;
                break;
            case ENTITY_SEEKER_M:
                this.seekerHeadTargetPosVariance = 0.0D;
                this.seekerHeadTargetVelVariance = 0.0D;
                this.seekerHeadShouldTargetEntity = true;
                this.seekerHeadFOV = 1.0D;
                this.seekerHeadRange = 200.0D;
                var seekerEntityName = hardwareConfig.getSeekerEntityName();
                if (seekerEntityName == null) {
                    this.sensorHeadEntityFilter = null;
                } else {
                    this.sensorHeadEntityFilter =
                            (TypeFilter<Entity, ?>)
                                    Registries.ENTITY_TYPE.get(Identifier.of(seekerEntityName));
                }
                break;
            default:
                LOGGER.error("{}: unknown seeker", this.missile.getId());
                return;
        }
        switch (hardwareConfig.getInertialSystem()) {
            case DEFAULT_IMU:
                this.posVariance = 0.0D;
                this.velVariance = 0.0D;
                this.headingVariance = 0.0D;
                break;
            default:
                LOGGER.error("{}: unknown imu", this.missile.getId());
                return;
        }

        LOGGER.info("{}: loaded missile hardware config", this.missile.getId());
    }

    private int calculateCost(MissileHardwareConfig hardwareConfig) {
        assert this.missile != null;
        assert hardwareConfig != null;
        return 0;
    }

    private void applyControlInput(ControlInput controlInput) throws MissileDiscardedException {
        assert this.missile != null;
        assert controlInput != null;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;

        if (controlInput.getExplode()) {

            if (thisObject.getWorld() instanceof ServerWorld serverWorld) {
                thisObject.explodeAndRemove(serverWorld);
            } else {
                discardAndNotify();
            }
            throw new MissileDiscardedException();
        }
        if (controlInput.getDisarm()) {
            discardAndNotify();
            throw new MissileDiscardedException();
        }
        Vec2f rotTurn =
                new Vec2f((float) controlInput.getPitchTurn(), (float) controlInput.getYawTurn());
        float len = rotTurn.length();
        // clamp without changing the direction of turn
        if (len > this.maxRotationInput) {
            rotTurn.multiply(this.maxRotationInput / len);
        }
        this.setPitch(this.getPitch() + rotTurn.x);
        this.setYaw(this.getYaw() + rotTurn.y);
    }

    // Update velocity and position of the missile.
    private void applyFlightDynamics() {
        // TODO: remove
        LOGGER.info("applying flight dynamics, time: ", this.tickCount);
        assert this.missile != null;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;

        // apply rotation variance
        thisObject.setPitch(
                thisObject.getPitch()
                        + (float) this.random.nextGaussian() * this.rotationVariance.floatValue());
        thisObject.setYaw(
                thisObject.getYaw()
                        + (float) this.random.nextGaussian() * this.rotationVariance.floatValue());

        Vec3d heading = thisObject.getRotationVector(-thisObject.getPitch(), -thisObject.getYaw());
        Vec3d acc =
                this.gravity.add(
                        heading.multiply(
                                this.accelerationCurve.apply(this.tickCount)
                                        * (1.0D
                                                + this.random.nextGaussian()
                                                        * this.accelerationRelVariance)));
        Vec3d vel = thisObject.getVelocity().add(acc);
        Vec3d velWithDrag = vel.multiply(1.0D - this.drag);
        thisObject.setVelocity(velWithDrag);
        thisObject.move(MovementType.SELF, velWithDrag);
        // set the velocity twice as the velocity might be changed when colliding
        // the original firework rocket code does this, too
        thisObject.setVelocity(velWithDrag);
        thisObject.velocityDirty = true;
    }

    private MissileState constructMissileState() {
        assert this.missile != null;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;

        Vec3d pos = thisObject.getPos();
        Vec3d vel = thisObject.getVelocity();
        double pitch = thisObject.getPitch();
        double yaw = thisObject.getYaw();
        return MissileState.newBuilder()
                .setTime(this.tickCount)
                .setPosX(pos.x + this.random.nextGaussian() * this.posVariance)
                .setPosY(pos.y + this.random.nextGaussian() * this.posVariance)
                .setPosZ(pos.z + this.random.nextGaussian() * this.posVariance)
                .setVelX(vel.x + this.random.nextGaussian() * this.velVariance)
                .setVelY(vel.y + this.random.nextGaussian() * this.velVariance)
                .setVelZ(vel.z + this.random.nextGaussian() * this.velVariance)
                .setPitch(pitch + this.random.nextGaussian() * this.headingVariance)
                .setYaw(yaw + this.random.nextGaussian() * this.headingVariance)
                // TODO: seeker output
                .setTargetLock(false)
                .setDestroyed(false)
                .setMissile(this.missile)
                .build();
    }

    private void sendMissileState() {
        assert this.missile != null;
        GuidanceStubManager.getInstance().sendMissileState(constructMissileState());
    }

    // This completely replaces the original Minecraft tick method.
    private void missileTick() {
        assert this.missile != null;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;

        super.tick();
        LOGGER.info("missile tick {}", this.tickCount);

        // <- missile tick begins
        //  <- client applies control input
        //   <- client applies missile dynamics
        //    <- client sends state
        //         <- server sends control
        //             <- missile tick begins
        //              <- client applies control input
        //               <- client applies missile dynamics
        //                <- client sends state
        if (this.tickCount == 0) {
            this.launchMissile();
        } else {
            try {
                readControlInput();
                applyFlightDynamics();
                sendMissileState();
            } catch (MissileDiscardedException e) {
                // increase life out of courtesy before the end
                ++tickCount;
                return;
            }
        }
        // entity collision check //
        // We can always hit, this::canHit would be cleaner though.
        // Do this after the update to ensure we don't hit ourself at launch.
        HitResult hitResult = ProjectileUtil.getCollision(thisObject, e -> true);
        // In the original code this is run after the movement is applied so do it like this here,
        // too.
        // TODO: block collision doesn't sometimes work when shot straight down
        // block collision check
        // this only does something when the rocket has explosion effects
        thisObject.tickBlockCollision();

        if (!thisObject.noClip
                && thisObject.isAlive()
                && hitResult.getType() != HitResult.Type.MISS) {
            thisObject.hitOrDeflect(hitResult);
            thisObject.velocityDirty = true;
        }

        // should detonate?
        if (this.tickCount >= this.timeToLive
                && thisObject.getWorld() instanceof ServerWorld serverWorld) {
            thisObject.explodeAndRemove(serverWorld);
        }

        // increase life
        ++tickCount;
    }

    private void discardAndNotify() {
        assert this.missile != null;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;
        LOGGER.info("discarding missile");
        GuidanceStubManager.getInstance()
                .endGuidanceConnection(
                        MissileState.newBuilder()
                                .setTime(this.tickCount)
                                .setDestroyed(true)
                                .setMissile(this.missile)
                                .build());
        thisObject.discard();
    }

    private void detonateWarhead(ServerWorld world) {
        assert this.missile != null;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;

        switch (this.warhead) {
            case BLANK:
                // do nothing
                break;
            case TNT_M:
                world.createExplosion(
                        thisObject,
                        thisObject.getX(),
                        thisObject.getY(),
                        thisObject.getZ(),
                        6,
                        World.ExplosionSourceType.TNT);
                break;
            default:
                LOGGER.error("{}: unknown warhead", this.missile.getId());
                return;
        }
    }

    @Inject(at = @At("HEAD"), method = "tick()V", cancellable = true)
    private void tickInject(CallbackInfo info) {
        if (tickCount == 0) {
            identifyMissile();
        }

        // overwrite original tick method
        if (this.missile != null) {
            this.missileTick();
            info.cancel();
        } else {
            ++tickCount;
        }
    }

    // overwrites both explodeAndRemove and explode for missiles
    @Inject(
            at = @At("HEAD"),
            method = "explodeAndRemove(Lnet/minecraft/server/world/ServerWorld;)V",
            cancellable = true)
    private void explodeAndRemoveInject(ServerWorld world, CallbackInfo info) {
        if (this.missile != null) {
            LOGGER.info("missile explode");
            detonateWarhead(world);
            this.discardAndNotify();
            info.cancel();
        }
    }
}
