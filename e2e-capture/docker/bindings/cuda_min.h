/*
 * The CUDA driver entry points the capture path calls.
 *
 * nv-codec-headers only gives us *typedefs* (tcuInit, tcuMemcpy2D_v2, ...)
 * because FFmpeg resolves these itself with GetProcAddress. jextract cannot
 * turn a function-pointer typedef into a downcall, so the prototypes are
 * declared here for real; they resolve against nvcuda.dll at runtime.
 *
 * Keep this list to what the capture path needs. Adding an entry here is the
 * one case where wrapping more of CUDA does require rerunning the build.
 */
#ifndef E2E_CAPTURE_CUDA_MIN_H
#define E2E_CAPTURE_CUDA_MIN_H

#include <ffnvcodec/dynlink_cuda.h>

/*
 * From cudaGL.h, which nv-codec-headers does not ship. These are stable public
 * values of the CUDA driver API.
 */
#define CU_GL_DEVICE_LIST_ALL                 0x01
#define CU_GL_DEVICE_LIST_CURRENT_FRAME       0x02
#define CU_GL_DEVICE_LIST_NEXT_FRAME          0x03

#define CU_GRAPHICS_REGISTER_FLAGS_NONE           0x00
#define CU_GRAPHICS_REGISTER_FLAGS_READ_ONLY      0x01
#define CU_GRAPHICS_REGISTER_FLAGS_WRITE_DISCARD  0x02

/* GL_TEXTURE_2D, so the example need not bind a GL header just for one enum. */
#define E2E_GL_TEXTURE_2D 0x0DE1

CUresult CUDAAPI cuInit(unsigned int Flags);
CUresult CUDAAPI cuDeviceGetName(char *name, int len, CUdevice dev);
CUresult CUDAAPI cuGetErrorName(CUresult error, const char **pStr);
CUresult CUDAAPI cuGetErrorString(CUresult error, const char **pStr);

/* Which CUDA device is driving the current GL context -- the machine may have
 * more than one GPU, and registering a texture across devices fails. */
CUresult CUDAAPI cuGLGetDevices(unsigned int *pCudaDeviceCount, CUdevice *pCudaDevices,
                                unsigned int cudaDeviceCount, unsigned int deviceList);

CUresult CUDAAPI cuCtxPushCurrent_v2(CUcontext ctx);
CUresult CUDAAPI cuCtxPopCurrent_v2(CUcontext *pctx);

CUresult CUDAAPI cuGraphicsGLRegisterImage(CUgraphicsResource *pCudaResource, unsigned int image,
                                           unsigned int target, unsigned int Flags);
CUresult CUDAAPI cuGraphicsUnregisterResource(CUgraphicsResource resource);
CUresult CUDAAPI cuGraphicsMapResources(unsigned int count, CUgraphicsResource *resources, CUstream hStream);
CUresult CUDAAPI cuGraphicsUnmapResources(unsigned int count, CUgraphicsResource *resources, CUstream hStream);
CUresult CUDAAPI cuGraphicsSubResourceGetMappedArray(CUarray *pArray, CUgraphicsResource resource,
                                                     unsigned int arrayIndex, unsigned int mipLevel);

CUresult CUDAAPI cuMemcpy2D_v2(const CUDA_MEMCPY2D *pCopy);
CUresult CUDAAPI cuStreamSynchronize(CUstream hStream);

#endif /* E2E_CAPTURE_CUDA_MIN_H */
