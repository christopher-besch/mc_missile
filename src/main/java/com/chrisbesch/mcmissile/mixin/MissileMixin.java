package com.chrisbesch.mcmissile.mixin;

import com.chrisbesch.mcmissile.guidance.GuidanceStubManager;
import com.chrisbesch.mcmissile.guidance.Missile;
import com.chrisbesch.mcmissile.guidance.MissileState;

import io.grpc.StatusRuntimeException;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FlyingItemEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
// import net.minecraft.entity.data.DataTracker;
// import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
// import net.minecraft.world.explosion.Explosion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FireworkRocketEntity.class)
public abstract class MissileMixin extends ProjectileEntity implements FlyingItemEntity {
    private static final Pattern namePattern = Pattern.compile("^mc_missile/(\\d\\d)/(.+)$");

    private static final String MOD_ID = "mc-missile";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // TODO: maybe make final or only create when needed
    private final Random random = Random.create();

    private int tickCount = 0;
    private int missileSelfDestructCount = 200;
    // set to true when this is detected
    private boolean isMissile = false;

    private String missileName;
    private int connectionId;
    private int missileId;

    // simulation parameters
    // the position is stored in the entity superclass
    private double dryMass = 30.0D;
    private double propellant = 5.0D;
    // this is a tiny rocket motor suitable for the Minecraft scale
    private final double exhaustVelocity = 1.0D;
    private final double exhaustVelocityVariance = 10.0D;
    // assuming Earth
    // divide by tick rate of 20
    // TODO: get actual tick rate
    // TODO: do something good here
    // private final Vec3d gravitationalAcceleration = new Vec3d(0.0D, -9.81D/20.0D, 0.0D);
    private final Vec3d gravitationalAcceleration = new Vec3d(0.0D, -0.1D, 0.0D);
    private final double dragCoefficient = 0.2D;
    private final double dragCoefficientVariance = 0.001D;
    // assuming sea-level
    private final double airDensity = 1.225D;
    private final double airDensityVariance = 0.05D;

    private Missile missile;

    // this constructor is only needed to make the compiler happy
    public MissileMixin(EntityType<? extends ProjectileEntity> entityType, World world) {
        super(entityType, world);
    }

    private void identifyMissile() {
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;

        // shotatangle when shot by crossbow or dispenser
        if (!thisObject.wasShotAtAngle()) {
            LOGGER.info("rocket wasn't fired at angle");
            this.isMissile = false;
            return;
        }

        List<FireworkExplosionComponent> explosions = thisObject.getExplosions();
        if (explosions.size() == 0) {
            LOGGER.info("rocket doesn't have explosives");
            this.isMissile = false;
            return;
        }
        // TODO: figuring out if the rocket has big balls isn't working
        // var bigBallCount = explosions.stream().filter(e -> e.shape ==
        // FireworkExplosionComponent.Type.BIG_BALL).count();
        // LOGGER.info("{}", bigBallCount);

        Text customName = thisObject.getStack().get(DataComponentTypes.CUSTOM_NAME);
        if (customName == null) {
            LOGGER.info("rocket doesn't have a custom name");
            this.isMissile = false;
            return;
        }

        Matcher matcher = namePattern.matcher(customName.getString());
        if (!matcher.matches()) {
            LOGGER.info("rocket's name doesn't match missile requirement");
            this.isMissile = false;
            return;
        }

        // TODO: check type of rocket
        // now we know this is a missile
        this.isMissile = true;

        try {
            this.connectionId = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            // this should never happen
            LOGGER.error("failed to convert connectionId in '{}'", customName.getString());
            this.isMissile = false;
            return;
        }
        this.missileName = matcher.group(2);
        // don't do negative numbers
        this.missileId = Math.abs(this.random.nextInt());
        LOGGER.info(
                "detected missile {} on socket id {}, missile id {}",
                this.missileName,
                this.connectionId,
                this.missileId);

        // TODO: set budget
        this.missile =
                Missile.newBuilder()
                        .setName(this.missileName)
                        .setId(this.missileId)
                        .setConnectionId(this.connectionId)
                        .setBudget(0)
                        .build();
    }

    // throws StatusRuntimeException
    private void launchMissile() {
        assert this.isMissile;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;

        LOGGER.info("missile launch");
        // TODO: this might not be required if this is not needed as we set the rotation below
        // set the rotation to be parallel with the initial velocity vector
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

        // add movement of crossbow owner
        Entity owner = thisObject.getOwner();
        if (owner != null) {
            LOGGER.info("applying owner velocity {}", owner.getVelocity());
            thisObject.setVelocity(getVelocity());
            thisObject.velocityDirty = true;
            // TODO: maybe add this back
            // thisObject.setVelocity(thisObject.getVelocity().add(owner.getVelocity()));
            // thisObject.velocityDirty = true;
            // TODO: figure out if this is needed
            // thisObject.move(MovementType.SELF, thisObject.getVelocity());
        } else {
            thisObject.setVelocity(Vec3d.ZERO);
            thisObject.velocityDirty = true;
        }
        GuidanceStubManager.getInstance().establishGuidanceConnection(constructMissileState());
    }

    // throws StatusRuntimeException
    private void controlMissile() {
        assert this.isMissile;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;

        // TODO: add noise
        Vec3d pos = thisObject.getPos();
        Vec3d vel = thisObject.getVelocity();
        double pitch = thisObject.getPitch();
        double yaw = thisObject.getYaw();
        // TODO: use return
        GuidanceStubManager.getInstance().consumeLatestControlInput(this.missile);
    }

    // update the position and velocity
    private void updateMissile() {
        assert this.isMissile;
        // TODO: maybe set life to 1 if the client receives that update, too
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;

        double oldPropellant = this.propellant;
        this.propellant = Math.max(0.0D, this.propellant - 0.5D);
        double spentPropellant = oldPropellant - this.propellant;
        LOGGER.info("spentPropellant: {}", spentPropellant);
        LOGGER.info("thrust fac: {}", spentPropellant * this.exhaustVelocity);
        double weight = this.dryMass + this.propellant;

        Vec3d heading = thisObject.getRotationVector(-thisObject.getPitch(), -thisObject.getYaw());
        // using Tsiolkovsky's rocket equation
        Vec3d thrust = heading.multiply(spentPropellant * this.exhaustVelocity);
        LOGGER.info("heading: {}", heading);
        // TODO: drag
        // Vec3d drag =
        Vec3d acceleration = (thrust.add(this.gravitationalAcceleration));
        LOGGER.info("acceleration: {}", acceleration);
        // TODO: clamp velocity because of Minecraft limitations
        // thisObject.velocityDirty = true;

        // vel = new Vec3d(1D, 0D, 0D);
        LOGGER.info("vel: {}", thisObject.getVelocity());
        // thisObject.setPosition(thisObject.getX() + vel.x, thisObject.getY() + vel.y,
        // thisObject.getZ() + vel.z);
        thisObject.setVelocity(thisObject.getVelocity().add(acceleration));
        thisObject.move(MovementType.SELF, thisObject.getVelocity());
        // set the velocity twice as the velocity might be changed when colliding
        // the original firework rocket code does this, too
        thisObject.setVelocity(thisObject.getVelocity().add(acceleration));
        thisObject.velocityDirty = true;

        // TODO: check rotation is correctly set
        // double posX = 0.0D;
        // double posY = 160.0D;
        // double posZ = 0.0D;
        // float yaw = (float) (MathHelper.atan2(velX, velZ) * 180.0F / (float) Math.PI);
        // float pitch = (float) (MathHelper.atan2(velY, Math.sqrt(velX * velX + velZ * velZ)) *
        // 180.0F
        // / (float) Math.PI);
        // // maybe use setPosition instead
        // thisObject.refreshPositionAndAngles(posX, posY, posZ, yaw, pitch);
    }

    private MissileState constructMissileState() {
        assert this.isMissile;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;
        Vec3d pos = thisObject.getPos();
        Vec3d vel = thisObject.getVelocity();
        double pitch = thisObject.getPitch();
        double yaw = thisObject.getYaw();
        return MissileState.newBuilder()
                .setPosX(pos.x)
                .setPosY(pos.y)
                .setPosZ(pos.z)
                .setVelX(vel.x)
                .setVelY(vel.y)
                .setVelZ(vel.z)
                .setPitch(pitch)
                .setYaw(yaw)
                .setTargetLock(false)
                .setDestroyed(false)
                .setMissile(this.missile)
                .build();
    }

    private void sendMissileState() {
        assert this.isMissile;
        GuidanceStubManager.getInstance().sendMissileState(constructMissileState());
    }

    // this completely replaces the original tick method
    // throws StatusRuntimeException
    private void missileTick() {
        assert this.isMissile;
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
            controlMissile();
            updateMissile();
            sendMissileState();
        }
        // entity collision check
        // we can always hit, this::canHit would be cleaner though
        // do this after the update to ensure we don't hit ourselfs at launch
        // TODO: hits with other rockets from same launcher create problems
        HitResult hitResult = ProjectileUtil.getCollision(thisObject, e -> true);
        // TODO: fix comment
        // in the original code this is run after the movement is applied so do it like this here,
        // too
        // TODO: block collision doesn't sometimes work when shot straight down
        // block collision check
        // this only does something when the rocket has explosion effects
        thisObject.tickBlockCollision();

        LOGGER.info("{}", hitResult.getType());
        if (!thisObject.noClip
                && thisObject.isAlive()
                && hitResult.getType() != HitResult.Type.MISS) {
            LOGGER.info("hit");
            thisObject.hitOrDeflect(hitResult);
            thisObject.velocityDirty = true;
        }

        // should detonate?
        if (this.tickCount >= this.missileSelfDestructCount
                && thisObject.getWorld() instanceof ServerWorld serverWorld) {
            thisObject.explodeAndRemove(serverWorld);
        }

        // increase life
        ++tickCount;
    }

    private void discardAndNotify() {
        assert this.isMissile;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;
        LOGGER.info("discarding missile");
        try {
            GuidanceStubManager.getInstance()
                    .endGuidanceConnection(
                            MissileState.newBuilder()
                                    .setDestroyed(true)
                                    .setMissile(this.missile)
                                    .build());
        } catch (StatusRuntimeException e) {
            LOGGER.error(
                    "failed to notify guidance and control server about discarded missile: {}",
                    e.getMessage());
            // don't discardAndNotify here because we're already there
        }
        thisObject.discard();
    }

    @Inject(at = @At("HEAD"), method = "tick()V", cancellable = true)
    private void tickInject(CallbackInfo info) {
        if (tickCount == 0) {
            identifyMissile();
        }

        // overwrite original tick method
        if (this.isMissile) {
            try {
                this.missileTick();
            } catch (StatusRuntimeException e) {
                LOGGER.error("connection to guidance control server failed: {}", e.getMessage());
                this.discardAndNotify();
            }
            info.cancel();
        } else {
            ++tickCount;
        }
    }

    @Inject(
            at = @At("HEAD"),
            method = "onEntityHit(Lnet/minecraft/util/hit/EntityHitResult;)V",
            cancellable = true)
    private void onEntityHitInject(EntityHitResult entityHitResult, CallbackInfo info) {
        LOGGER.info("missile entity hit");
    }

    @Inject(
            at = @At("HEAD"),
            method = "onBlockHit(Lnet/minecraft/util/hit/BlockHitResult;)V",
            cancellable = true)
    private void onBlockHitInject(BlockHitResult blockHitResult, CallbackInfo info) {
        LOGGER.info("missile block hit");
    }

    // this doesn't work
    // @Inject(at = @At("HEAD"), method =
    // "hitOrDeflect(Lnet/minecraft/util/hit/HitResult;)Lnet/minecraft/entity/ProjectileDeflection;",
    // cancellable = true)
    // private void hitOrDeflectInject(HitResult hitResult, CallbackInfo info) {
    //     LOGGER.info("missile hitOrDeflect hit");
    // }

    // overwrites both explodeAndRemove and explode for missiles
    @Inject(
            at = @At("HEAD"),
            method = "explodeAndRemove(Lnet/minecraft/server/world/ServerWorld;)V",
            cancellable = true)
    private void explodeAndRemoveInject(ServerWorld world, CallbackInfo info) {
        // TODO: check different types
        if (this.isMissile) {
            LOGGER.info("missile explode");
            FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;
            world.createExplosion(
                    thisObject,
                    thisObject.getX(),
                    thisObject.getY(),
                    thisObject.getZ(),
                    6,
                    World.ExplosionSourceType.TNT);
            this.discardAndNotify();
            info.cancel();
        }
    }

    // @Override
    // public void initDataTracker(DataTracker.Builder builder) {
    //     FireworkRocketEntity thisObject = (FireworkRocketEntity)(Object)this;
    //     thisObject.initDataTracker(builder);
    // }

    // @Override
    // public ItemStack getStack() {
    //     FireworkRocketEntity thisObject = (FireworkRocketEntity)(Object)this;
    //     return thisObject.getStack();
    // }
}
