/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect;

import static com.google.android.exoplayer2.util.Assertions.checkArgument;
import static com.google.android.exoplayer2.util.Assertions.checkState;
import static com.google.android.exoplayer2.util.Assertions.checkStateNotNull;
import static com.google.common.collect.Iterables.getLast;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.FrameInfo;
import androidx.media3.common.FrameProcessingException;
import androidx.media3.common.FrameProcessor;
import androidx.media3.common.SurfaceInfo;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.util.GlUtil;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.ColorInfo;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A {@link FrameProcessor} implementation that applies {@link GlEffect} instances using OpenGL on a
 * background thread.
 */
public final class GlEffectsFrameProcessor implements FrameProcessor {

  /** A factory for {@link GlEffectsFrameProcessor} instances. */
  public static class Factory implements FrameProcessor.Factory {
    /**
     * {@inheritDoc}
     *
     * <p>All {@link Effect} instances must be {@link GlEffect} instances.
     *
     * <p>Using HDR requires the {@code EXT_YUV_target} OpenGL extension.
     */
    @Override
    public GlEffectsFrameProcessor create(
        Context context,
        FrameProcessor.Listener listener,
        List<Effect> effects,
        DebugViewProvider debugViewProvider,
        ColorInfo colorInfo)
        throws FrameProcessingException {

      ExecutorService singleThreadExecutorService = Util.newSingleThreadExecutor(THREAD_NAME);

      Future<GlEffectsFrameProcessor> glFrameProcessorFuture =
          singleThreadExecutorService.submit(
              () ->
                  createOpenGlObjectsAndFrameProcessor(
                      context,
                      listener,
                      effects,
                      debugViewProvider,
                      colorInfo,
                      singleThreadExecutorService));

      try {
        return glFrameProcessorFuture.get();
      } catch (ExecutionException e) {
        throw new FrameProcessingException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new FrameProcessingException(e);
      }
    }
  }

  /**
   * Creates the OpenGL context, surfaces, textures, and framebuffers, initializes {@link
   * GlTextureProcessor} instances corresponding to the {@link GlEffect} instances, and returns a
   * new {@code GlEffectsFrameProcessor}.
   *
   * <p>All {@link Effect} instances must be {@link GlEffect} instances.
   *
   * <p>This method must be executed using the {@code singleThreadExecutorService}, as later OpenGL
   * commands will be called on that thread.
   */
  @WorkerThread
  private static GlEffectsFrameProcessor createOpenGlObjectsAndFrameProcessor(
      Context context,
      FrameProcessor.Listener listener,
      List<Effect> effects,
      DebugViewProvider debugViewProvider,
      ColorInfo colorInfo,
      ExecutorService singleThreadExecutorService)
      throws GlUtil.GlException, FrameProcessingException {
    checkState(Thread.currentThread().getName().equals(THREAD_NAME));

    // TODO(b/237674316): Delay initialization of things requiring the colorInfo, to
    //  configure based on the color info from the decoder output media format instead.
    boolean useHdr = ColorInfo.isTransferHdr(colorInfo);
    EGLDisplay eglDisplay = GlUtil.createEglDisplay();
    EGLContext eglContext =
        useHdr
            ? GlUtil.createEglContextEs3Rgba1010102(eglDisplay)
            : GlUtil.createEglContext(eglDisplay);

    if (GlUtil.isSurfacelessContextExtensionSupported()) {
      GlUtil.focusEglSurface(
          eglDisplay, eglContext, EGL14.EGL_NO_SURFACE, /* width= */ 1, /* height= */ 1);
    } else if (useHdr) {
      GlUtil.focusPlaceholderEglSurfaceRgba1010102(eglContext, eglDisplay);
    } else {
      GlUtil.focusPlaceholderEglSurface(eglContext, eglDisplay);
    }

    ImmutableList<GlTextureProcessor> textureProcessors =
        getGlTextureProcessorsForGlEffects(
            context, effects, eglDisplay, eglContext, listener, debugViewProvider, colorInfo);
    FrameProcessingTaskExecutor frameProcessingTaskExecutor =
        new FrameProcessingTaskExecutor(singleThreadExecutorService, listener);
    chainTextureProcessorsWithListeners(textureProcessors, frameProcessingTaskExecutor, listener);

    return new GlEffectsFrameProcessor(
        eglDisplay, eglContext, frameProcessingTaskExecutor, textureProcessors);
  }

  /**
   * Combines consecutive {@link GlMatrixTransformation} instances into a single {@link
   * MatrixTransformationProcessor} and converts all other {@link GlEffect} instances to separate
   * {@link GlTextureProcessor} instances.
   *
   * <p>All {@link Effect} instances must be {@link GlEffect} instances.
   *
   * @return A non-empty list of {@link GlTextureProcessor} instances to apply in the given order.
   *     The first is an {@link ExternalTextureProcessor} and the last is a {@link
   *     FinalMatrixTransformationProcessorWrapper}.
   */
  // TODO(b/239757183): Squash GlMatrixTransformation and RgbMatrix together.
  private static ImmutableList<GlTextureProcessor> getGlTextureProcessorsForGlEffects(
      Context context,
      List<Effect> effects,
      EGLDisplay eglDisplay,
      EGLContext eglContext,
      FrameProcessor.Listener listener,
      DebugViewProvider debugViewProvider,
      ColorInfo colorInfo)
      throws FrameProcessingException {
    ImmutableList.Builder<GlTextureProcessor> textureProcessorListBuilder =
        new ImmutableList.Builder<>();
    ImmutableList.Builder<GlMatrixTransformation> matrixTransformationListBuilder =
        new ImmutableList.Builder<>();
    ImmutableList.Builder<RgbMatrix> rgbaMatrixTransformationListBuilder =
        new ImmutableList.Builder<>();
    boolean sampleFromExternalTexture = true;
    for (int i = 0; i < effects.size(); i++) {
      Effect effect = effects.get(i);
      checkArgument(effect instanceof GlEffect, "GlEffectsFrameProcessor only supports GlEffects");
      GlEffect glEffect = (GlEffect) effect;
      // The following logic may change the order of the RgbMatrix and GlMatrixTransformation
      // effects. This does not influence the output since RgbMatrix only changes the individual
      // pixels and does not take any location in account, which the GlMatrixTransformation
      // may change.
      if (glEffect instanceof GlMatrixTransformation) {
        matrixTransformationListBuilder.add((GlMatrixTransformation) glEffect);
        continue;
      }
      if (glEffect instanceof RgbMatrix) {
        rgbaMatrixTransformationListBuilder.add((RgbMatrix) glEffect);
        continue;
      }
      ImmutableList<GlMatrixTransformation> matrixTransformations =
          matrixTransformationListBuilder.build();
      if (!matrixTransformations.isEmpty() || sampleFromExternalTexture) {
        textureProcessorListBuilder.add(
            new MatrixTransformationProcessor(
                context,
                matrixTransformations,
                sampleFromExternalTexture,
                colorInfo,
                /* outputOpticalColors= */ false));
        matrixTransformationListBuilder = new ImmutableList.Builder<>();
        sampleFromExternalTexture = false;
      }
      ImmutableList<RgbMatrix> rgbaMatrixTransformations =
          rgbaMatrixTransformationListBuilder.build();
      if (!rgbaMatrixTransformations.isEmpty()) {
        textureProcessorListBuilder.add(
            new RgbMatrixProcessor(
                context, rgbaMatrixTransformations, ColorInfo.isTransferHdr(colorInfo)));
        rgbaMatrixTransformationListBuilder = new ImmutableList.Builder<>();
      }
      textureProcessorListBuilder.add(
          glEffect.toGlTextureProcessor(context, ColorInfo.isTransferHdr(colorInfo)));
    }

    ImmutableList<RgbMatrix> rgbaMatrixTransformations =
        rgbaMatrixTransformationListBuilder.build();
    if (!rgbaMatrixTransformations.isEmpty()) {
      // Add a MatrixTransformationProcessor if none yet exists for sampling from an external
      // texture.
      if (sampleFromExternalTexture) {
        // TODO(b/239757183): Remove the unnecessary MatrixTransformationProcessor after it got
        // merged with RgbMatrixProcessor.
        textureProcessorListBuilder.add(
            new MatrixTransformationProcessor(
                context,
                ImmutableList.of(),
                sampleFromExternalTexture,
                colorInfo,
                /* outputOpticalColors= */ false));
        sampleFromExternalTexture = false;
      }
      textureProcessorListBuilder.add(
          new RgbMatrixProcessor(
              context, rgbaMatrixTransformations, ColorInfo.isTransferHdr(colorInfo)));
    }

    textureProcessorListBuilder.add(
        new FinalMatrixTransformationProcessorWrapper(
            context,
            eglDisplay,
            eglContext,
            matrixTransformationListBuilder.build(),
            listener,
            debugViewProvider,
            sampleFromExternalTexture,
            colorInfo));
    return textureProcessorListBuilder.build();
  }

  /**
   * Chains the given {@link GlTextureProcessor} instances using {@link
   * ChainingGlTextureProcessorListener} instances.
   */
  private static void chainTextureProcessorsWithListeners(
      ImmutableList<GlTextureProcessor> textureProcessors,
      FrameProcessingTaskExecutor frameProcessingTaskExecutor,
      FrameProcessor.Listener frameProcessorListener) {
    for (int i = 0; i < textureProcessors.size() - 1; i++) {
      GlTextureProcessor producingGlTextureProcessor = textureProcessors.get(i);
      GlTextureProcessor consumingGlTextureProcessor = textureProcessors.get(i + 1);
      ChainingGlTextureProcessorListener chainingGlTextureProcessorListener =
          new ChainingGlTextureProcessorListener(
              producingGlTextureProcessor,
              consumingGlTextureProcessor,
              frameProcessingTaskExecutor);
      producingGlTextureProcessor.setOutputListener(chainingGlTextureProcessorListener);
      producingGlTextureProcessor.setErrorListener(frameProcessorListener::onFrameProcessingError);
      consumingGlTextureProcessor.setInputListener(chainingGlTextureProcessorListener);
    }
  }

  private static final String THREAD_NAME = "Effect:GlThread";
  private static final long RELEASE_WAIT_TIME_MS = 100;

  private final EGLDisplay eglDisplay;
  private final EGLContext eglContext;
  private final FrameProcessingTaskExecutor frameProcessingTaskExecutor;
  private final ExternalTextureManager inputExternalTextureManager;
  private final Surface inputSurface;
  private final FinalMatrixTransformationProcessorWrapper finalTextureProcessorWrapper;
  private final ImmutableList<GlTextureProcessor> allTextureProcessors;

  private @MonotonicNonNull FrameInfo nextInputFrameInfo;
  private boolean inputStreamEnded;
  /**
   * Offset compared to original media presentation time that has been added to incoming frame
   * timestamps, in microseconds.
   */
  private long previousStreamOffsetUs;

  private GlEffectsFrameProcessor(
      EGLDisplay eglDisplay,
      EGLContext eglContext,
      FrameProcessingTaskExecutor frameProcessingTaskExecutor,
      ImmutableList<GlTextureProcessor> textureProcessors)
      throws FrameProcessingException {

    this.eglDisplay = eglDisplay;
    this.eglContext = eglContext;
    this.frameProcessingTaskExecutor = frameProcessingTaskExecutor;

    checkState(!textureProcessors.isEmpty());
    checkState(textureProcessors.get(0) instanceof ExternalTextureProcessor);
    checkState(getLast(textureProcessors) instanceof FinalMatrixTransformationProcessorWrapper);
    ExternalTextureProcessor inputExternalTextureProcessor =
        (ExternalTextureProcessor) textureProcessors.get(0);
    inputExternalTextureManager =
        new ExternalTextureManager(inputExternalTextureProcessor, frameProcessingTaskExecutor);
    inputExternalTextureProcessor.setInputListener(inputExternalTextureManager);
    inputSurface = new Surface(inputExternalTextureManager.getSurfaceTexture());
    finalTextureProcessorWrapper =
        (FinalMatrixTransformationProcessorWrapper) getLast(textureProcessors);
    allTextureProcessors = textureProcessors;
    previousStreamOffsetUs = C.TIME_UNSET;
  }

  @Override
  public Surface getInputSurface() {
    return inputSurface;
  }

  @Override
  public void setInputFrameInfo(FrameInfo inputFrameInfo) {
    nextInputFrameInfo = adjustForPixelWidthHeightRatio(inputFrameInfo);

    if (nextInputFrameInfo.streamOffsetUs != previousStreamOffsetUs) {
      finalTextureProcessorWrapper.appendStream(nextInputFrameInfo.streamOffsetUs);
      previousStreamOffsetUs = nextInputFrameInfo.streamOffsetUs;
    }
  }

  @Override
  public void registerInputFrame() {
    checkState(!inputStreamEnded);
    checkStateNotNull(
        nextInputFrameInfo, "setInputFrameInfo must be called before registering input frames");

    inputExternalTextureManager.registerInputFrame(nextInputFrameInfo);
  }

  @Override
  public int getPendingInputFrameCount() {
    return inputExternalTextureManager.getPendingFrameCount();
  }

  @Override
  public void setOutputSurfaceInfo(@Nullable SurfaceInfo outputSurfaceInfo) {
    finalTextureProcessorWrapper.setOutputSurfaceInfo(outputSurfaceInfo);
  }

  @Override
  public void signalEndOfInput() {
    checkState(!inputStreamEnded);
    inputStreamEnded = true;
    frameProcessingTaskExecutor.submit(inputExternalTextureManager::signalEndOfInput);
  }

  @Override
  public void release() {
    try {
      frameProcessingTaskExecutor.release(
          /* releaseTask= */ this::releaseTextureProcessorsAndDestroyGlContext,
          RELEASE_WAIT_TIME_MS);
    } catch (InterruptedException unexpected) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(unexpected);
    }
    inputExternalTextureManager.release();
    inputSurface.release();
  }

  /**
   * Expands or shrinks the frame based on the {@link FrameInfo#pixelWidthHeightRatio} and returns a
   * new {@link FrameInfo} instance with scaled dimensions and {@link
   * FrameInfo#pixelWidthHeightRatio} of {@code 1}.
   */
  private FrameInfo adjustForPixelWidthHeightRatio(FrameInfo frameInfo) {
    if (frameInfo.pixelWidthHeightRatio > 1f) {
      return new FrameInfo(
          (int) (frameInfo.width * frameInfo.pixelWidthHeightRatio),
          frameInfo.height,
          /* pixelWidthHeightRatio= */ 1,
          frameInfo.streamOffsetUs);
    } else if (frameInfo.pixelWidthHeightRatio < 1f) {
      return new FrameInfo(
          frameInfo.width,
          (int) (frameInfo.height / frameInfo.pixelWidthHeightRatio),
          /* pixelWidthHeightRatio= */ 1,
          frameInfo.streamOffsetUs);
    } else {
      return frameInfo;
    }
  }

  /**
   * Releases the {@link GlTextureProcessor} instances and destroys the OpenGL context.
   *
   * <p>This method must be called on the {@linkplain #THREAD_NAME background thread}.
   */
  @WorkerThread
  private void releaseTextureProcessorsAndDestroyGlContext()
      throws GlUtil.GlException, FrameProcessingException {
    for (int i = 0; i < allTextureProcessors.size(); i++) {
      allTextureProcessors.get(i).release();
    }
    GlUtil.destroyEglContext(eglDisplay, eglContext);
  }
}
