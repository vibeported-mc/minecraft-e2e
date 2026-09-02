/*
 * Stand-in for the CUDA SDK's <cuda.h>.
 *
 * libavutil/hwcontext_cuda.h opens with `#include <cuda.h>` because downstream
 * users are expected to have the CUDA toolkit. We deliberately do not -- the
 * whole point of nv-codec-headers is that NVENC/NVDEC/CUDA are reached through
 * the driver at runtime -- but the two types that header actually needs
 * (CUcontext, CUstream) are declared by nv-codec-headers just the same.
 *
 * Putting this directory ahead of the FFmpeg include path is what lets
 * hwcontext_cuda.h parse, which is what gets AVCUDADeviceContext (and its
 * cuda_ctx field, the handle GL interop hangs off) into the bindings.
 */
#ifndef E2E_CAPTURE_CUDA_H
#define E2E_CAPTURE_CUDA_H

#include <ffnvcodec/dynlink_cuda.h>

#endif /* E2E_CAPTURE_CUDA_H */
