package com.chrisbesch.mcmissile.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.FlyingItemEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
// import net.minecraft.world.explosion.Explosion;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import net.minecraft.entity.projectile.ProjectileUtil;
// import net.minecraft.entity.data.DataTracker;
// import net.minecraft.item.ItemStack;
import net.minecraft.entity.EntityType;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
// import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.entity.MovementType;

import net.minecraft.component.type.FireworkExplosionComponent;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.entity.ProjectileDeflection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;
import java.lang.Math;
import java.util.regex.Matcher;
import java.util.List;

@Mixin(FireworkRocketEntity.class)
public abstract class MissileMixin extends ProjectileEntity implements FlyingItemEntity {
    private static final Pattern namePattern = Pattern.compile("^mc_missile/(\\d\\d)/(.+)$");

    private static final String MOD_ID = "mc-missile";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // TODO: maybe make final or only create when needed
    private final Random random = Random.create();

    private int tickCount = 0;
    private int missileSelfDestructCount = 100;
    // set to true when this is detected
    private boolean isMissile = false;

    private String missileName;
    private int socketId;
    private int missileId;

    // simulation parameters
    // the position is stored in the entity superclass
    // can't be called velocity because a superclass already has an atribute with that name
    private Vec3d missileVelocity;
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
    private final Vec3d gravitationalAcceleration = new Vec3d(0.0D, -0.2D, 0.0D);
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
        FireworkRocketEntity thisObject = (FireworkRocketEntity)(Object)this;

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
        // var bigBallCount = explosions.stream().filter(e -> e.shape == FireworkExplosionComponent.Type.BIG_BALL).count();
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
           this.socketId = Integer.parseInt(matcher.group(1));
        }
        catch (NumberFormatException e) {
            // this should never happen
            LOGGER.error("failed to convert socketId in '{}'", customName.getString());
            this.isMissile = false;
            return;
        }
        this.missileName = matcher.group(2);
        this.missileId = this.random.nextInt();
        LOGGER.info("detected missile {} on socket id {}, missile id {}", this.missileName, this.socketId, this.missileId);
    }

    private void launchMissile() {
        assert this.isMissile;
        FireworkRocketEntity thisObject = (FireworkRocketEntity)(Object)this;

        LOGGER.info("missile launch");
        // TODO: this might not be required if this is not needed as we set the rotation below
        // set the rotation to be parallel with the initial velocity vector
        thisObject.updateRotation();
        if (!thisObject.isSilent()) {
            LOGGER.info("sound");
            thisObject.getWorld().playSound(null, thisObject.getX(), thisObject.getY(), thisObject.getZ(),
                    SoundEvents.ENTITY_ENDER_DRAGON_SHOOT, SoundCategory.AMBIENT, 20.0F, 1.0F);
        }

        thisObject.setVelocity(Vec3d.ZERO);
        thisObject.velocityDirty = true;

        // add movement of crossbow owner
        Entity owner = thisObject.getOwner();
        if (owner != null) {
            LOGGER.info("applying owner velocity {}", owner.getVelocity());
            this.missileVelocity = owner.getVelocity();
            // TODO: maybe add this back
            // thisObject.setVelocity(thisObject.getVelocity().add(owner.getVelocity()));
            // thisObject.velocityDirty = true;
            // TODO: figure out if this is needed
            // thisObject.move(MovementType.SELF, thisObject.getVelocity());
        }
        // TODO: establish connection
    }

    // update the position and velocity
    private void updateMissile() {
        assert this.isMissile;
        // TODO: maybe set life to 1 if the client receives that update, too
        FireworkRocketEntity thisObject = (FireworkRocketEntity)(Object)this;

        double oldPropellant = this.propellant;
        this.propellant = Math.max(0.0D, this.propellant-0.5D);
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
        this.missileVelocity = this.missileVelocity.add(acceleration);
        // thisObject.setVelocity(vel);
        // thisObject.velocityDirty = true;

        thisObject.setVelocity(this.missileVelocity);
        thisObject.velocityDirty = true;

        // vel = new Vec3d(1D, 0D, 0D);
        LOGGER.info("vel: {}", this.missileVelocity);
        // thisObject.setPosition(thisObject.getX() + vel.x, thisObject.getY() + vel.y, thisObject.getZ() + vel.z);
        thisObject.move(MovementType.SELF, this.missileVelocity);

        // TODO: check rotation is correctly set
        // double posX = 0.0D;
        // double posY = 160.0D;
        // double posZ = 0.0D;
        // float yaw = (float) (MathHelper.atan2(velX, velZ) * 180.0F / (float) Math.PI);
        // float pitch = (float) (MathHelper.atan2(velY, Math.sqrt(velX * velX + velZ * velZ)) * 180.0F / (float) Math.PI);
        // // maybe use setPosition instead
        // thisObject.refreshPositionAndAngles(posX, posY, posZ, yaw, pitch);
    }

    // this completely replaces the original tick method
    private void missileTick() {
        assert this.isMissile;
        FireworkRocketEntity thisObject = (FireworkRocketEntity)(Object)this;

        super.tick();
        LOGGER.info("missile tick {}", this.tickCount);

        if (this.tickCount == 0) {
            this.launchMissile();
        }

        if (this.tickCount != 0) {
            // TODO: use control data from connection
            updateMissile();
        }
        // entity collision check
        // we can always hit, this::canHit would be cleaner though
        // do this after the update to ensure we don't hit ourselfs at launch
        // TODO: hits with other rockets from same launcher create problems
        HitResult hitResult = ProjectileUtil.getCollision(thisObject, e -> true);
        // TODO: fix comment
        // in the original code this is run after the movement is applied so do it like this here, too
        // TODO: block collision doesn't sometimes work when shot straight down
        // block collision check
        // this only does something when the rocket has explosion effects
        thisObject.tickBlockCollision();

        LOGGER.info("{}", hitResult.getType());
        if (!thisObject.noClip && thisObject.isAlive() && hitResult.getType() != HitResult.Type.MISS) {
            LOGGER.info("hit");
            thisObject.hitOrDeflect(hitResult);
            thisObject.velocityDirty = true;
        }

        // should detonate?
        if (this.tickCount >= this.missileSelfDestructCount && thisObject.getWorld() instanceof ServerWorld serverWorld) {
            thisObject.explodeAndRemove(serverWorld);
        }

        // increase life
        ++tickCount;
    }

    @Inject(at = @At("HEAD"), method = "tick()V", cancellable = true)
    private void tickInject(CallbackInfo info) {
        if (tickCount == 0) {
            identifyMissile();
        }

        // overwrite original tick method
        if (this.isMissile) {
            this.missileTick();
            info.cancel();
        } else {
            ++tickCount;
        }
    }

    @Inject(at = @At("HEAD"), method = "onEntityHit(Lnet/minecraft/util/hit/EntityHitResult;)V", cancellable = true)
    private void onEntityHitInject(EntityHitResult entityHitResult, CallbackInfo info) {
        LOGGER.info("missile entity hit");
    }

    @Inject(at = @At("HEAD"), method = "onBlockHit(Lnet/minecraft/util/hit/BlockHitResult;)V", cancellable = true)
    private void onBlockHitInject(BlockHitResult blockHitResult, CallbackInfo info) {
        LOGGER.info("missile block hit");
    }
    
    // this doesn't work
    // @Inject(at = @At("HEAD"), method = "hitOrDeflect(Lnet/minecraft/util/hit/HitResult;)Lnet/minecraft/entity/ProjectileDeflection;", cancellable = true)
    // private void hitOrDeflectInject(HitResult hitResult, CallbackInfo info) {
    //     LOGGER.info("missile hitOrDeflect hit");
    // }

    @Inject(at = @At("HEAD"), method = "explode(Lnet/minecraft/server/world/ServerWorld;)V", cancellable = true)
    private void explodeInject(ServerWorld world, CallbackInfo info) {
        // TODO: check different types
        if (this.isMissile) {
            LOGGER.info("missile explode");
            FireworkRocketEntity thisObject = (FireworkRocketEntity)(Object)this;
            world.createExplosion(thisObject, thisObject.getX(), thisObject.getY(), thisObject.getZ(), 6, World.ExplosionSourceType.TNT);
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
