package draylar.gateofbabylon.entity;

import draylar.gateofbabylon.registry.GOBEntities;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.network.ServerSidePacketRegistry;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class SpearProjectileEntity extends PersistentProjectileEntity {

    public static final Identifier ENTITY_ID = new Identifier("gateofbabylon", "spear");

    private static final TrackedData<Byte> LOYALTY = DataTracker.registerData(SpearProjectileEntity.class, TrackedDataHandlerRegistry.BYTE);
    private static final TrackedData<Boolean> ENCHANTED = DataTracker.registerData(SpearProjectileEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<ItemStack> STACK = DataTracker.registerData(SpearProjectileEntity.class, TrackedDataHandlerRegistry.ITEM_STACK);

    private ItemStack stack = ItemStack.EMPTY;
    private boolean dealtDamage;
    public int returnTimer;

    public SpearProjectileEntity(EntityType<? extends SpearProjectileEntity> entityType, World world) {
        super(entityType, world);
        this.stack = new ItemStack(Items.TRIDENT);
    }

    public SpearProjectileEntity(World world, LivingEntity owner, ItemStack stack) {
        super(GOBEntities.SPEAR, owner, world);
        this.stack = stack.copy();

        this.dataTracker.set(LOYALTY, (byte) EnchantmentHelper.getLoyalty(stack));
        this.dataTracker.set(ENCHANTED, stack.hasGlint());
        this.dataTracker.set(STACK, stack);
    }

    @Environment(EnvType.CLIENT)
    public SpearProjectileEntity(World world, double x, double y, double z) {
        super(GOBEntities.SPEAR, x, y, z, world);
        this.updatePosition(x, y, z);
        this.updateTrackedPosition(x, y, z);
    }

    @Override
    public void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(LOYALTY, (byte)0);
        this.dataTracker.startTracking(ENCHANTED, false);
        this.dataTracker.startTracking(STACK, ItemStack.EMPTY);
    }

    @Override
    public void tick() {
        if (this.inGroundTime > 4) {
            this.dealtDamage = true;
        }

        Entity entity = this.getOwner();
        if ((this.dealtDamage || this.isNoClip()) && entity != null) {
            int i = (Byte)this.dataTracker.get(LOYALTY);
            if (i > 0 && !this.isOwnerAlive()) {
                if (!this.world.isClient && this.pickupType == PersistentProjectileEntity.PickupPermission.ALLOWED) {
                    this.dropStack(this.asItemStack(), 0.1F);
                }

                this.remove();
            } else if (i > 0) {
                this.setNoClip(true);
                Vec3d vec3d = new Vec3d(entity.getX() - this.getX(), entity.getEyeY() - this.getY(), entity.getZ() - this.getZ());
                this.setPos(this.getX(), this.getY() + vec3d.y * 0.015D * (double)i, this.getZ());
                if (this.world.isClient) {
                    this.lastRenderY = this.getY();
                }

                double d = 0.05D * (double)i;
                this.setVelocity(this.getVelocity().multiply(0.95D).add(vec3d.normalize().multiply(d)));
                if (this.returnTimer == 0) {
                    this.playSound(SoundEvents.ITEM_TRIDENT_RETURN, 10.0F, 1.0F);
                }

                ++this.returnTimer;
            }
        }

        super.tick();
    }

    private boolean isOwnerAlive() {
        Entity entity = this.getOwner();
        if (entity != null && entity.isAlive()) {
            return !(entity instanceof ServerPlayerEntity) || !entity.isSpectator();
        } else {
            return false;
        }
    }

    @Override
    public ItemStack asItemStack() {
        return this.stack.copy();
    }

    public ItemStack getStack() {
        return world.isClient ? dataTracker.get(STACK) : stack;
    }

    @Environment(EnvType.CLIENT)
    public boolean isEnchanted() {
        return (Boolean)this.dataTracker.get(ENCHANTED);
    }

    @Override
    public EntityHitResult getEntityCollision(Vec3d currentPosition, Vec3d nextPosition) {
        return this.dealtDamage ? null : super.getEntityCollision(currentPosition, nextPosition);
    }

    @Override
    public void onEntityHit(EntityHitResult entityHitResult) {
        Entity entity = entityHitResult.getEntity();
        float f = 8.0F;
        if (entity instanceof LivingEntity) {
            LivingEntity livingEntity = (LivingEntity)entity;
            f += EnchantmentHelper.getAttackDamage(this.stack, livingEntity.getGroup());
        }

        Entity entity2 = this.getOwner();
        DamageSource damageSource = DamageSource.trident(this, (Entity)(entity2 == null ? this : entity2));
        this.dealtDamage = true;
        SoundEvent soundEvent = SoundEvents.ITEM_TRIDENT_HIT;
        if (entity.damage(damageSource, f)) {
            if (entity.getType() == EntityType.ENDERMAN) {
                return;
            }

            if (entity instanceof LivingEntity) {
                LivingEntity livingEntity2 = (LivingEntity)entity;
                if (entity2 instanceof LivingEntity) {
                    EnchantmentHelper.onUserDamaged(livingEntity2, entity2);
                    EnchantmentHelper.onTargetDamaged((LivingEntity)entity2, livingEntity2);
                }

                this.onHit(livingEntity2);
            }
        }

        this.setVelocity(this.getVelocity().multiply(-0.01D, -0.1D, -0.01D));
        float g = 1.0F;
        if (this.world instanceof ServerWorld && this.world.isThundering() && EnchantmentHelper.hasChanneling(this.stack)) {
            BlockPos blockPos = entity.getBlockPos();
            if (this.world.isSkyVisible(blockPos)) {
                LightningEntity lightningEntity = (LightningEntity)EntityType.LIGHTNING_BOLT.create(this.world);
                lightningEntity.refreshPositionAfterTeleport(Vec3d.ofBottomCenter(blockPos));
                lightningEntity.setChanneler(entity2 instanceof ServerPlayerEntity ? (ServerPlayerEntity)entity2 : null);
                this.world.spawnEntity(lightningEntity);
                soundEvent = SoundEvents.ITEM_TRIDENT_THUNDER;
                g = 5.0F;
            }
        }

        this.playSound(soundEvent, g, 1.0F);
    }

    @Override
    public SoundEvent getHitSound() {
        return SoundEvents.ITEM_TRIDENT_HIT_GROUND;
    }

    @Override
    public void onPlayerCollision(PlayerEntity player) {
        Entity entity = this.getOwner();
        if (entity == null || entity.getUuid() == player.getUuid()) {
            super.onPlayerCollision(player);
        }
    }

    @Override
    public void readCustomDataFromTag(CompoundTag tag) {
        super.readCustomDataFromTag(tag);
        if (tag.contains("Stack", 10)) {
            this.stack = ItemStack.fromTag(tag.getCompound("Stack"));
        }

        this.dealtDamage = tag.getBoolean("DealtDamage");
        this.dataTracker.set(LOYALTY, (byte)EnchantmentHelper.getLoyalty(this.stack));
    }

    @Override
    public void writeCustomDataToTag(CompoundTag tag) {
        super.writeCustomDataToTag(tag);
        tag.put("Stack", this.stack.toTag(new CompoundTag()));
        tag.putBoolean("DealtDamage", this.dealtDamage);
    }

    @Override
    public void age() {
        int i = (Byte)this.dataTracker.get(LOYALTY);
        if (this.pickupType != PersistentProjectileEntity.PickupPermission.ALLOWED || i <= 0) {
            super.age();
        }

    }

    @Override
    public Packet<?> createSpawnPacket() {
        PacketByteBuf packet = new PacketByteBuf(Unpooled.buffer());

        packet.writeDouble(this.getX());
        packet.writeDouble(this.getY());
        packet.writeDouble(this.getZ());

        packet.writeInt(this.getEntityId());

        return ServerSidePacketRegistry.INSTANCE.toPacket(ENTITY_ID, packet);
    }

    @Override
    public float getDragInWater() {
        return 0.99F;
    }

    @Environment(EnvType.CLIENT)
    @Override
    public boolean shouldRender(double cameraX, double cameraY, double cameraZ) {
        return true;
    }
}
