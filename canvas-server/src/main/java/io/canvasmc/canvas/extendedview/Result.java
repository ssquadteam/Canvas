package io.canvasmc.canvas.extendedview;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

public sealed interface Result permits Result.Success, Result.NotGenerated, Result.Failure {
    boolean isFailure();

    boolean isNotGenerated();

    boolean isSuccess();

    ClientboundLevelChunkWithLightPacket getPacketOrThrow();

    record Failure(Throwable cause) implements Result {
        @Override
        public boolean isFailure() {
            return true;
        }

        @Override
        public boolean isNotGenerated() {
            return false;
        }

        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public ClientboundLevelChunkWithLightPacket getPacketOrThrow() {
            throw new IllegalStateException("Chunk failed to load", this.cause);
        }
    }

    record NotGenerated() implements Result {
        public static final NotGenerated INSTANCE = new NotGenerated();

        @Override
        public boolean isFailure() {
            return false;
        }

        @Override
        public boolean isNotGenerated() {
            return true;
        }

        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public ClientboundLevelChunkWithLightPacket getPacketOrThrow() {
            throw new IllegalStateException("Chunk is not generated");
        }
    }

    record Success(ClientboundLevelChunkWithLightPacket packet) implements Result {
        @Override
        public boolean isFailure() {
            return false;
        }

        @Override
        public boolean isNotGenerated() {
            return false;
        }

        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public ClientboundLevelChunkWithLightPacket getPacketOrThrow() {
            return this.packet;
        }
    }
}
