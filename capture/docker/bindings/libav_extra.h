/*
 * Values the capture path needs that FFmpeg spells as *function-like* macros,
 * which jextract cannot evaluate and so does not bind.
 *
 * Restating them as object macros here keeps them coming from FFmpeg's own
 * headers for this exact build, instead of being hand-copied into Java where
 * nothing would ever check them again. AVERROR(EAGAIN) in particular is not a
 * fixed number: it is negated errno, and errno values are the C library's.
 */
#ifndef E2E_CAPTURE_LIBAV_EXTRA_H
#define E2E_CAPTURE_LIBAV_EXTRA_H

#include <errno.h>
#include <libavutil/error.h>

/* avcodec_send_frame / avcodec_receive_packet return these constantly. */
#define E2E_AVERROR_EAGAIN AVERROR(EAGAIN)
#define E2E_AVERROR_ENOMEM AVERROR(ENOMEM)
#define E2E_AVERROR_EINVAL AVERROR(EINVAL)

#endif /* E2E_CAPTURE_LIBAV_EXTRA_H */
