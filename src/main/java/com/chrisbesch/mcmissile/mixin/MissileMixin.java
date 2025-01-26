package com.chrisbesch.mcmissile.mixin;

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

import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;
import java.lang.Math;
import java.util.regex.Matcher;

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
        // thisObject.updateRotation(); is not needed as we set the rotation below
        if (!thisObject.isSilent()) {
            LOGGER.info("sound");
            thisObject.getWorld().playSound(null, thisObject.getX(), thisObject.getY(), thisObject.getZ(),
                    SoundEvents.ENTITY_ENDER_DRAGON_SHOOT, SoundCategory.AMBIENT, 20.0F, 1.0F);
        }
        // TODO: establish connection
    }

    // update the position and velocity
    private void updateMissile() {
        assert this.isMissile;
        // TODO: maybe set life to 1 if the client receives that update, too
        FireworkRocketEntity thisObject = (FireworkRocketEntity)(Object)this;

        // TODO: use data from connection
        double velX = 1.0D;
        double velY = 0.0D;
        double velZ = 0.0D;
        // TODO: maybe set velocity directly without normalizing and then scaling again
        float length = (float) Math.sqrt(velX*velX + velY*velY + velZ*velZ);
        thisObject.setVelocity(velX, velY, velZ, length, 0.0F);

        // TODO: check rotation is correctly set
        double posX = 0.0D;
        double posY = 160.0D;
        double posZ = 0.0D;
        float yaw = (float) (MathHelper.atan2(velX, velZ) * 180.0F / (float) Math.PI);
        float pitch = (float) (MathHelper.atan2(velY, Math.sqrt(velX * velX + velZ * velZ)) * 180.0F / (float) Math.PI);
        // maybe use setPosition instead
        thisObject.refreshPositionAndAngles(posX, posY, posZ, yaw, pitch);
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

        // entity collision check
        // we can always hit, this::canHit would be cleaner though
        HitResult hitResult = ProjectileUtil.getCollision(thisObject, e -> true);
        updateMissile();
        // in the original code this is run after the movement is applied so do it like this here, too
        // block collision check
        thisObject.tickBlockCollision();

        if (!thisObject.noClip && thisObject.isAlive() && hitResult.getType() != HitResult.Type.MISS) {
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

    // @Inject(at = @At("HEAD"), method = "onEntityHit(Lnet/minecraft/util/hit/EntityHitResult;)V", cancellable = true)
    // private void onEntityHitInject(EntityHitResult entityHitResult, CallbackInfo info) {
    //     LOGGER.info("missile entity hit");
    // }

    // @Inject(at = @At("HEAD"), method = "onBlockHit(Lnet/minecraft/util/hit/BlockHitResult;)V", cancellable = true)
    // private void onBlockHitInject(BlockHitResult blockHitResult, CallbackInfo info) {
    //     LOGGER.info("missile block hit");
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
