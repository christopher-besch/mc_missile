package com.chrisbesch.mcmissile.mixin;

import com.chrisbesch.mcmissile.guidance.ControlInput;
import com.chrisbesch.mcmissile.guidance.GuidanceStubManager;
import com.chrisbesch.mcmissile.guidance.Missile;
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
// import net.minecraft.entity.data.DataTracker;
// import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
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
    private static final String MOD_ID = "mc-missile";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Pattern MISSILE_NAME_PATTERN =
            Pattern.compile("^mc_missile/(\\d\\d)/(.+)$");

    private final Random random = Random.create();

    private int tickCount = 0;

    // set iff this is a missile
    private Missile missile = null;

    // flight parameters //
    private int missileSelfDestructCount = 200;

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
        // TODO: figuring out if the rocket has big balls isn't working
        // var bigBallCount = explosions.stream().filter(e -> e.shape ==
        // FireworkExplosionComponent.Type.BIG_BALL).count();
        // LOGGER.info("{}", bigBallCount);

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
                        .setBudget(0);

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
            thisObject.setVelocity(getVelocity());
            thisObject.velocityDirty = true;
        } else {
            thisObject.setVelocity(Vec3d.ZERO);
            thisObject.velocityDirty = true;
        }
        GuidanceStubManager.getInstance().establishGuidanceConnection(constructMissileState());
    }

    private void controlMissile() {
        assert this.missile != null;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;

        var controlInput =
                GuidanceStubManager.getInstance().consumeLatestControlInput(this.missile);
        if (controlInput != null) {
            applyControlInput(controlInput);
        }
    }

    private void applyControlInput(ControlInput controlInput) {
        assert this.missile != null;
        assert controlInput != null;
        // TODO: implement
    }

    // Update velocity and position of the missile.
    private void applyFlightDynamics() {
        assert this.missile != null;
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
        assert this.missile != null;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;

        Vec3d pos = thisObject.getPos();
        Vec3d vel = thisObject.getVelocity();
        double pitch = thisObject.getPitch();
        double yaw = thisObject.getYaw();
        return MissileState.newBuilder()
                .setTime(this.tickCount)
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
            controlMissile();
            applyFlightDynamics();
            sendMissileState();
        }
        // entity collision check //
        // We can always hit, this::canHit would be cleaner though.
        // Do this after the update to ensure we don't hit ourself at launch.
        // TODO: hits with other rockets from same launcher create problems
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
            // TODO: why is this needed?
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
        assert this.missile != null;
        FireworkRocketEntity thisObject = (FireworkRocketEntity) (Object) this;
        LOGGER.info("discarding missile");
        GuidanceStubManager.getInstance()
                .endGuidanceConnection(
                        MissileState.newBuilder()
                                .setDestroyed(true)
                                .setMissile(this.missile)
                                .build());
        thisObject.discard();
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
        // TODO: check warhead config
        if (this.missile != null) {
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
}
