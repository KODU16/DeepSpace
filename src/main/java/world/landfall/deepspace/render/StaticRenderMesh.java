package world.landfall.deepspace.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.function.Consumer;

/** Owns one lazily uploaded static GPU mesh and draws it with a changing model-view matrix. */
final class StaticRenderMesh implements AutoCloseable {
    private final VertexFormat format;
    private final VertexFormat.Mode mode;
    private final int initialCapacity;
    private final Consumer<BufferBuilder> geometryWriter;
    private VertexBuffer vertexBuffer;
    private boolean built;

    StaticRenderMesh(
            VertexFormat format,
            VertexFormat.Mode mode,
            int initialCapacity,
            Consumer<BufferBuilder> geometryWriter
    ) {
        this.format = format;
        this.mode = mode;
        this.initialCapacity = initialCapacity;
        this.geometryWriter = geometryWriter;
    }

    void draw(RenderType renderType, Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        ensureBuilt();
        if (this.vertexBuffer == null) {
            return;
        }

        renderType.setupRenderState();
        try {
            var shader = RenderSystem.getShader();
            if (shader == null) {
                return;
            }
            this.vertexBuffer.bind();
            this.vertexBuffer.drawWithShader(modelViewMatrix, projectionMatrix, shader);
        } finally {
            VertexBuffer.unbind();
            renderType.clearRenderState();
        }
    }

    void draw(RenderType renderType, PoseStack poseStack) {
        // Buffered rendering applies the current RenderSystem matrix after the CPU-side pose transform.
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix())
                .mul(poseStack.last().pose());
        draw(renderType, modelView, RenderSystem.getProjectionMatrix());
    }

    void draw(RenderType renderType, PoseStack poseStack, Matrix4fc projectionMatrix) {
        // Sky-stage meshes must use the event projection that matched the current frustum.
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix())
                .mul(poseStack.last().pose());
        draw(renderType, modelView, new Matrix4f(projectionMatrix));
    }

    private void ensureBuilt() {
        if (this.built) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        try (ByteBufferBuilder memory = new ByteBufferBuilder(this.initialCapacity)) {
            BufferBuilder builder = new BufferBuilder(memory, this.mode, this.format);
            this.geometryWriter.accept(builder);
            MeshData mesh = builder.build();
            if (mesh == null) {
                this.built = true;
                return;
            }
            this.vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            try {
                this.vertexBuffer.bind();
                this.vertexBuffer.upload(mesh);
                this.built = true;
            } catch (RuntimeException exception) {
                this.vertexBuffer.close();
                this.vertexBuffer = null;
                throw exception;
            } finally {
                VertexBuffer.unbind();
            }
        }
    }

    @Override
    public void close() {
        if (this.vertexBuffer != null) {
            this.vertexBuffer.close();
            this.vertexBuffer = null;
        }
        this.built = false;
    }
}
