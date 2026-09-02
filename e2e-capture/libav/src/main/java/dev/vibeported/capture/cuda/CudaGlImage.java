package dev.vibeported.capture.cuda;

import dev.vibeported.capture.libav.Frame;
import dev.vibeported.capture.libav.gen.CUDA_MEMCPY2D;
import dev.vibeported.capture.libav.gen.libav;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * An OpenGL texture registered with CUDA, and the copy out of it.
 *
 * <p>This is the whole GPU-to-encoder hop. The texture storage is mapped into
 * the CUDA address space and copied device-to-device into the frame the encoder
 * will read. Nothing crosses the PCIe bus and nothing touches host memory.
 */
public final class CudaGlImage implements AutoCloseable {

    private final Arena arena = Arena.ofConfined();
    private final CudaDevice device;
    private final MemorySegment resource;   // CUgraphicsResource*
    private final MemorySegment copy;       // a reused CUDA_MEMCPY2D
    private final MemorySegment arrayHolder;
    private boolean closed;

    CudaGlImage(CudaDevice device, int textureId) {
        this.device = device;
        this.resource = arena.allocate(libav.C_POINTER);
        this.arrayHolder = arena.allocate(libav.C_POINTER);
        this.copy = CUDA_MEMCPY2D.allocate(arena);

        CudaRuntime.pushContext(device.context());
        try {
            CudaException.check("cuGraphicsGLRegisterImage",
                    libav.cuGraphicsGLRegisterImage(resource, textureId,
                            libav.E2E_GL_TEXTURE_2D(),
                            libav.CU_GRAPHICS_REGISTER_FLAGS_READ_ONLY()));
        } finally {
            CudaRuntime.popContext();
        }
    }

    /**
     * Copies the current contents of the texture into a frame.
     *
     * <p>The caller must have finished rendering to the texture first, with a
     * glFinish or a fence: CUDA has no view of the OpenGL queue.
     *
     * @param frame a hardware frame whose plane 0 is packed 32-bit RGB
     */
    public void copyInto(Frame frame) {
        if (closed) throw new IllegalStateException("Texture already unregistered");
        MemorySegment stream = device.stream();

        CudaRuntime.pushContext(device.context());
        try {
            CudaException.check("cuGraphicsMapResources",
                    libav.cuGraphicsMapResources(1, resource, stream));
            try {
                CudaException.check("cuGraphicsSubResourceGetMappedArray",
                        libav.cuGraphicsSubResourceGetMappedArray(arrayHolder,
                                resource.get(libav.C_POINTER, 0), 0, 0));

                CUDA_MEMCPY2D.srcMemoryType(copy, libav.CU_MEMORYTYPE_ARRAY());
                CUDA_MEMCPY2D.srcArray(copy, arrayHolder.get(libav.C_POINTER, 0));
                CUDA_MEMCPY2D.srcXInBytes(copy, 0);
                CUDA_MEMCPY2D.srcY(copy, 0);

                CUDA_MEMCPY2D.dstMemoryType(copy, libav.CU_MEMORYTYPE_DEVICE());
                CUDA_MEMCPY2D.dstDevice(copy, frame.plane(0).address());
                CUDA_MEMCPY2D.dstPitch(copy, frame.linesize(0));
                CUDA_MEMCPY2D.dstXInBytes(copy, 0);
                CUDA_MEMCPY2D.dstY(copy, 0);

                // 4 bytes per pixel: the texture is GL_RGBA8 and the frame is
                // packed 32-bit RGB. The same bytes, so no conversion.
                CUDA_MEMCPY2D.WidthInBytes(copy, (long) frame.width() * 4);
                CUDA_MEMCPY2D.Height(copy, frame.height());

                CudaException.check("cuMemcpy2D", libav.cuMemcpy2D_v2(copy));
            } finally {
                CudaException.check("cuGraphicsUnmapResources",
                        libav.cuGraphicsUnmapResources(1, resource, stream));
            }
            // The encoder reads this frame on its own schedule, so make sure
            // the copy has landed before it can.
            CudaException.check("cuStreamSynchronize", libav.cuStreamSynchronize(stream));
        } finally {
            CudaRuntime.popContext();
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        CudaRuntime.pushContext(device.context());
        try {
            libav.cuGraphicsUnregisterResource(resource.get(libav.C_POINTER, 0));
        } finally {
            CudaRuntime.popContext();
            arena.close();
        }
    }
}
