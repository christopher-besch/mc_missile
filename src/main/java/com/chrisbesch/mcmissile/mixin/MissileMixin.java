package com.chrisbesch.mcmissile.mixin;

import com.chrisbesch.mcmissile.Hardware;
import com.chrisbesch.mcmissile.MissileDiscardedException;
import com.chrisbesch.mcmissile.guidance.ControlInput;
import com.chrisbesch.mcmissile.guidance.GuidanceStubManager;
import com.chrisbesch.mcmissile.guidance.Missile;
import com.chrisbesch.mcmissile.guidance.MissileState;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FlyingItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(FireworkRocketEntity.class)
public abstract class MissileMixin extends ProjectileEntity implements FlyingItemEntity {
    // position is stored in ProjectileEntity
    // velocity is stored in ProjectileEntity
    // rotation is stored in ProjectileEntity
    private static final String MOD_ID = "mc-missile";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Pattern MISSILE_NAME_PATTERN = Pattern.compile("^m/(\\d\\d)/(.+)$");
    private static final int GLOWING_TICKS = 20 * 20;

    private final Random random = Random.create();

    // load default hardware at start
    private Hardware hardware = new Hardware();

    private int tickCount = 0;

    // set iff this is a missile
    private Missile missile;

    // flight parameters //
    private Vec3d gravity = new Vec3d(0.0D, -0.2D, 0.0D);

    private Entity seekerHeadEntityLock;

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
                        .setBudget(
                                Hardware.calculateBudget(
                                        explosions,
                                        thisObject
                                                .getStack()
                                                .get(DataComponentTypes.FIREWORKS)
                                                .flightDuration()));

        try {
            missileBuilder.setConnectionId(Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException e) {
            // this should never happen
            LOGGER.error("failed to convert connectionId in '{}'", customName.getString());
            return;
        }

        this.missile = missileBuilder.build();
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
        lockIRSeeker();
        GuidanceStubManager.getInstance().establishGuidanceConnection(constructMissileState());
    }

    private void readControlInput() throws MissileDiscardedException {
        LOGGER.info("reading control input: {}", this.tickCount);
        assert this.missile != null;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;

        var controlInput =
                GuidanceStubManager.getInstance().consumeLatestControlInput(this.missile);
        if (controlInput != null) {
            // Only load the config directly after launch.
            // When there is no config given, use the default.
            if (this.tickCount == 1 && controlInput.getHardwareConfig() != null) {
                LOGGER.info("loading hardware config from guidance server");
                var requestedHardware = new Hardware(controlInput.getHardwareConfig());
                var cost = requestedHardware.calculateCost();
                if (cost > this.missile.getBudget()) {
                    LOGGER.warn(
                            "{}: missile is too expensive {}, budget only {}",
                            this.missile.getId(),
                            cost,
                            this.missile.getBudget());
                } else {
                    this.hardware = requestedHardware;
                }
            } else {
                // TODO: remove
                LOGGER.info("don't load hardware config from guidance server this time");
            }
            applyControlInput(controlInput);
        }
    }

    // Always run this just before we sent the missile state to the guidance server.
    private void lockIRSeeker() {
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;
        // only lock onto first target
        if (this.seekerHeadEntityLock != null) {
            return;
        }
        if (!this.hardware.seekerHeadShouldTargetEntity) {
            return;
        }
        LOGGER.info("attempting target lock");

        World world = thisObject.getWorld();
        if (!(thisObject.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        Vec3d pos = thisObject.getPos();

        List<? extends Entity> possible_targets =
                serverWorld.getEntitiesByType(
                        this.hardware.sensorHeadEntityFilter,
                        // search in a large box around the missile
                        new Box(
                                pos.getX() - this.hardware.seekerHeadRange,
                                pos.getY() - this.hardware.seekerHeadRange,
                                pos.getZ() - this.hardware.seekerHeadRange,
                                pos.getX() + this.hardware.seekerHeadRange,
                                pos.getY() + this.hardware.seekerHeadRange,
                                pos.getZ() + this.hardware.seekerHeadRange),
                        possible_target ->
                                (this.canSee((Entity) possible_target))
                                        // check the target is not out of range (i.e. in the corners
                                        // of the big box)
                                        && pos.subtract(possible_target.getPos()).lengthSquared()
                                                <= (this.hardware.seekerHeadRange
                                                        * this.hardware.seekerHeadRange)
                                        // don't target yourself
                                        && possible_target != thisObject.getOwner());
        this.seekerHeadEntityLock = minAngleTarget(possible_targets);
        if (this.seekerHeadEntityLock != null
                && this.seekerHeadEntityLock instanceof LivingEntity) {
            ((LivingEntity) this.seekerHeadEntityLock)
                    .addStatusEffect(
                            new StatusEffectInstance(StatusEffects.GLOWING, GLOWING_TICKS));
        }
    }

    private Entity minAngleTarget(List<? extends Entity> targets) {
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;
        Vec3d missile_heading =
                thisObject
                        .getRotationVector(-thisObject.getPitch(), -thisObject.getYaw())
                        .normalize();
        Entity bestTarget = null;
        // only accept values that are not outside our field of view
        double maxDotProd = Math.cos(Math.toRadians(this.hardware.seekerHeadFOV));
        LOGGER.info("maxDotProd: {}", maxDotProd);
        for (Entity entity : targets) {
            Vec3d dir_to_target = entity.getPos().subtract(thisObject.getPos()).normalize();
            double dotProd = missile_heading.dotProduct(dir_to_target);
            // TODO: remove
            LOGGER.info("dotProd: {}", dotProd);
            // overwrite old data to include edge case
            if (dotProd >= maxDotProd) {
                maxDotProd = dotProd;
                bestTarget = entity;
            }
        }
        // TODO: remove
        LOGGER.info("maxDotProd: {}", maxDotProd);
        return bestTarget;
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
                                        new Vec3d(entity.getX(), entity.getEyeY(), entity.getZ()),
                                        RaycastContext.ShapeType.COLLIDER,
                                        RaycastContext.FluidHandling.NONE,
                                        this))
                        .getType()
                == HitResult.Type.MISS;
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
        if (len > this.hardware.maxRotationInput) {
            rotTurn.multiply(this.hardware.maxRotationInput / len);
        }
        this.setPitch(this.getPitch() + rotTurn.x);
        this.setYaw(this.getYaw() + rotTurn.y);
    }

    // Update velocity and position of the missile.
    private void applyFlightDynamics() {
        // TODO: remove
        LOGGER.info("applying flight dynamics, time: {}", this.tickCount);
        assert this.missile != null;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;

        // apply rotation variance
        thisObject.setPitch(
                thisObject.getPitch()
                        // Don't apply rotation variance on the first tick -> give the seeker head
                        // some chance of locking onto the target.
                        + (this.tickCount <= 1
                                ? 0.0F
                                : (float) this.random.nextGaussian()
                                        * this.hardware.rotationVariance));
        thisObject.setYaw(
                thisObject.getYaw()
                        + (this.tickCount <= 1
                                ? 0.0F
                                : (float) this.random.nextGaussian()
                                        * this.hardware.rotationVariance));

        Vec3d heading = thisObject.getRotationVector(-thisObject.getPitch(), -thisObject.getYaw());
        Vec3d acc =
                this.gravity.add(
                        heading.multiply(
                                this.hardware.accelerationCurve.apply(this.tickCount)
                                        * (1.0D
                                                + this.random.nextGaussian()
                                                        * this.hardware.accelerationRelVariance)));
        Vec3d vel = thisObject.getVelocity().add(acc);
        Vec3d velWithDrag = vel.multiply(1.0D - this.hardware.drag);
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
        var builder =
                MissileState.newBuilder()
                        .setTime(this.tickCount)
                        .setPosX(pos.x + this.random.nextGaussian() * this.hardware.posVariance)
                        .setPosY(pos.y + this.random.nextGaussian() * this.hardware.posVariance)
                        .setPosZ(pos.z + this.random.nextGaussian() * this.hardware.posVariance)
                        .setVelX(vel.x + this.random.nextGaussian() * this.hardware.velVariance)
                        .setVelY(vel.y + this.random.nextGaussian() * this.hardware.velVariance)
                        .setVelZ(vel.z + this.random.nextGaussian() * this.hardware.velVariance)
                        .setPitch(
                                pitch + this.random.nextGaussian() * this.hardware.headingVariance)
                        .setYaw(yaw + this.random.nextGaussian() * this.hardware.headingVariance)
                        .setDestroyed(false)
                        .setMissile(this.missile);
        if (this.seekerHeadEntityLock != null) {
            // TODO: remove
            LOGGER.info("target lock");
            builder.setTargetLock(true);
            if (this.canSee(this.seekerHeadEntityLock)) {
                var lockPos = this.seekerHeadEntityLock.getPos();
                var lockVel = this.seekerHeadEntityLock.getVelocity();
                builder.setTargetPosX(
                                lockPos.x
                                        + this.random.nextGaussian()
                                                * this.hardware.seekerHeadTargetPosVariance)
                        .setTargetPosY(
                                lockPos.y
                                        + this.random.nextGaussian()
                                                * this.hardware.seekerHeadTargetPosVariance)
                        .setTargetPosZ(
                                lockPos.z
                                        + this.random.nextGaussian()
                                                * this.hardware.seekerHeadTargetPosVariance)
                        .setTargetVelX(
                                lockVel.x
                                        + this.random.nextGaussian()
                                                * this.hardware.seekerHeadTargetVelVariance)
                        .setTargetVelY(
                                lockVel.y
                                        + this.random.nextGaussian()
                                                * this.hardware.seekerHeadTargetVelVariance)
                        .setTargetVelZ(
                                lockVel.z
                                        + this.random.nextGaussian()
                                                * this.hardware.seekerHeadTargetVelVariance)
                        .setTargetVisible(true);
            } else {
                builder.setTargetVisible(false);
            }
        } else {
            builder.setTargetLock(false).setTargetVisible(false);
        }
        return builder.build();
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
                lockIRSeeker();
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
        if (this.tickCount >= this.hardware.timeToLive
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

        if (this.hardware.shouldDetonate) {
            world.createExplosion(
                    thisObject,
                    thisObject.getX(),
                    thisObject.getY(),
                    thisObject.getZ(),
                    this.hardware.detonationPower,
                    World.ExplosionSourceType.TNT);
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
