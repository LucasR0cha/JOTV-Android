package com.joabtv.app.ui.movies

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Efeito de Melhorar imagem do player. Uma única passada na placa de vídeo faz a nitidez, as cores, o contraste e o brilho, e com o
 * Upscaler ligado a mesma passada amplia o vídeo até a tela (SGSR). Os números ficam em [params] e são lidos a cada quadro, então
 * mexer na barra do painel vale no quadro seguinte sem refazer o caminho de vídeo do player. Só existe quando a pessoa liga algo.
 */
@UnstableApi
internal class ImageEnhanceEffect(
    /** Liga o Upscaler. Fica fixo na criação, porque o tamanho de saída só é decidido quando o caminho é montado. */
    val upscale: Boolean = false,
    /** Tamanho da superfície do vídeo na tela, que diz até onde dá para ampliar. */
    val targetWidth: Int = 0,
    val targetHeight: Int = 0,
) : GlEffect {
    @Volatile
    var params: ImageShaderParams = ImageShaderParams.NONE

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try {
            ImageEnhanceProgram(this, useHdr)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
}

@UnstableApi
private class ImageEnhanceProgram(private val effect: ImageEnhanceEffect, useHdr: Boolean) :
    BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ useHdr, /* texturePoolCapacity = */ 1) {
    private val program = GlProgram(VERTEX, FRAGMENT)
    // O SGSR só é compilado quando algum vídeo precisa de ampliação. Sem Upscaler nada disso é criado.
    private var sgsr: GlProgram? = null
    private var upscaling = false
    private var width = 1
    private var height = 1

    init {
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth.coerceAtLeast(1)
        height = inputHeight.coerceAtLeast(1)
        val out = if (effect.upscale) upscaleOutput(width, height, effect.targetWidth, effect.targetHeight) else null
        upscaling = out != null
        return if (out != null) Size(out.first, out.second) else Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val p = effect.params
            val active = if (upscaling) sgsrProgram() else program
            active.use()
            active.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            if (upscaling) {
                active.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
                active.setFloatsUniform("uPt", floatArrayOf(1f / width, 1f / height))
                // A barra de nitidez do painel vira a firmeza da borda do SGSR, de 1 (só amplia) até 2 (o máximo dele).
                active.setFloatUniform("uEdgeSharpness", 1f + p.sharp)
            } else {
                active.setFloatsUniform("uTexel", floatArrayOf(1f / width, 1f / height))
                active.setFloatUniform("uSharp", p.sharp)
            }
            active.setFloatUniform("uContrast", p.contrast)
            active.setFloatUniform("uSaturation", p.saturation)
            active.setFloatUniform("uBrightness", p.brightness)
            active.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun sgsrProgram(): GlProgram = sgsr ?: GlProgram(VERTEX, FRAGMENT_SGSR).also {
        it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        sgsr = it
    }

    override fun release() {
        super.release()
        try {
            program.delete()
            sgsr?.delete()
        } catch (_: GlUtil.GlException) {
            // O contexto já pode ter fechado junto com o player.
        }
    }

    private companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

        /**
         * Nitidez no estilo do CAS da AMD (FidelityFX, licença MIT) com a cruz de cinco pontos. O realce de cada canal é limitado pelo
         * quanto ainda cabe antes de estourar o preto ou o branco, então bordas já fortes não ganham halo e o ruído não vira areia.
         * Depois vêm as cores, o contraste em torno do cinza médio e o brilho.
         */
        const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uSharp;
uniform float uContrast;
uniform float uSaturation;
uniform float uBrightness;
varying vec2 vTexSamplingCoord;

void main() {
  vec2 p = vTexSamplingCoord;
  vec3 c = texture2D(uTexSampler, p).rgb;
  if (uSharp > 0.0) {
    vec3 n = texture2D(uTexSampler, p + vec2(0.0, -uTexel.y)).rgb;
    vec3 s = texture2D(uTexSampler, p + vec2(0.0, uTexel.y)).rgb;
    vec3 w = texture2D(uTexSampler, p + vec2(-uTexel.x, 0.0)).rgb;
    vec3 e = texture2D(uTexSampler, p + vec2(uTexel.x, 0.0)).rgb;
    vec3 lo = min(min(min(n, s), min(w, e)), c);
    vec3 hi = max(max(max(n, s), max(w, e)), c);
    vec3 room = min(lo, 1.0 - hi) / max(hi, 0.0001);
    vec3 k = sqrt(clamp(room, 0.0, 1.0)) * (-1.0 / mix(8.0, 5.0, uSharp));
    c = clamp((c + (n + s + w + e) * k) / (1.0 + 4.0 * k), 0.0, 1.0);
  }
  float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
  c = mix(vec3(luma), c, uSaturation);
  c = (c - 0.5) * uContrast + 0.5 + uBrightness;
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""

        /**
         * Snapdragon Game Super Resolution 1 (SGSR), da Qualcomm, no GLSL ES 1.00 que o contexto de efeitos do Media3 sempre aceita.
         * Portado do shader de mpv do catálogo do computador (mesmo algoritmo, com a variante de direção de borda). Aqui o vídeo chega
         * em RGB, então a borda é medida na luma e o ajuste dela é somado aos três canais sobre a base bilinear. O textureGather vira
         * 12 leituras de texel, nos mesmos pontos e com os mesmos pesos. Depois vêm as cores, o contraste e o brilho.
         *
         * Copyright (c) 2023, Qualcomm Innovation Center, Inc. All rights reserved.
         * SPDX-License-Identifier: BSD-3-Clause
         */
        const val FRAGMENT_SGSR = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec2 uSize;
uniform vec2 uPt;
uniform float uEdgeSharpness;
uniform float uContrast;
uniform float uSaturation;
uniform float uBrightness;
varying vec2 vTexSamplingCoord;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float lumaAt(vec2 ij) { return luma(texture2D(uTexSampler, (ij + 0.5) * uPt).rgb); }

float fastLanczos2(float x) {
  float wA = x - 4.0;
  float wB = x * wA - wA;
  wA *= wA;
  return wB * wA;
}

vec2 weightY(float dx, float dy, float c, float stdv, vec2 dir) {
  float edgeDis = dx * dir.y + dy * dir.x;
  float x = edgeDis * edgeDis * (clamp(c * c * stdv, 0.0, 1.0) * 0.7 - 1.0) + (dx * dx + dy * dy);
  float w = fastLanczos2(x);
  return vec2(w, w * c);
}

vec2 edgeDirection(vec4 left, vec4 right) {
  vec2 delta;
  delta.x = (right.x - left.z) + (right.w - left.y);
  delta.y = (right.x - left.z) - (right.w - left.y);
  return delta * inversesqrt(dot(delta, delta) + 3.075740e-05);
}

void main() {
  vec2 uv = vTexSamplingCoord;
  vec3 base = texture2D(uTexSampler, uv).rgb;
  float cl = luma(base);
  vec2 imgCoord = uv * uSize + vec2(-0.5, 0.5);
  vec2 px = floor(imgCoord);
  vec2 pl = imgCoord - px;
  vec4 left = vec4(lumaAt(px + vec2(-1.0, 0.0)), lumaAt(px), lumaAt(px + vec2(0.0, -1.0)), lumaAt(px + vec2(-1.0, -1.0)));
  float edgeVote = abs(left.z - left.y) + abs(cl - left.y) + abs(cl - left.z);
  vec3 c = base;
  if (edgeVote > 4.0 / 255.0) {
    vec4 right = vec4(lumaAt(px + vec2(1.0, 0.0)), lumaAt(px + vec2(2.0, 0.0)), lumaAt(px + vec2(2.0, -1.0)), lumaAt(px + vec2(1.0, -1.0)));
    vec4 upDown = vec4(lumaAt(px + vec2(0.0, -2.0)), lumaAt(px + vec2(1.0, -2.0)), lumaAt(px + vec2(1.0, 1.0)), lumaAt(px + vec2(0.0, 1.0)));
    float mean = (left.y + left.z + right.x + right.w) * 0.25;
    left -= vec4(mean);
    right -= vec4(mean);
    upDown -= vec4(mean);
    float centerDelta = cl - mean;
    float sum = max(dot(abs(left) + abs(right) + abs(upDown), vec4(1.0)), 0.00001);
    float sumMean = 10.14185 / sum;
    float stdv = sumMean * sumMean;
    vec2 dir = edgeDirection(left, right);
    vec2 aWY = weightY(pl.x, pl.y + 1.0, upDown.x, stdv, dir);
    aWY += weightY(pl.x - 1.0, pl.y + 1.0, upDown.y, stdv, dir);
    aWY += weightY(pl.x - 1.0, pl.y - 2.0, upDown.z, stdv, dir);
    aWY += weightY(pl.x, pl.y - 2.0, upDown.w, stdv, dir);
    aWY += weightY(pl.x + 1.0, pl.y - 1.0, left.x, stdv, dir);
    aWY += weightY(pl.x, pl.y - 1.0, left.y, stdv, dir);
    aWY += weightY(pl.x, pl.y, left.z, stdv, dir);
    aWY += weightY(pl.x + 1.0, pl.y, left.w, stdv, dir);
    aWY += weightY(pl.x - 1.0, pl.y - 1.0, right.x, stdv, dir);
    aWY += weightY(pl.x - 2.0, pl.y - 1.0, right.y, stdv, dir);
    aWY += weightY(pl.x - 2.0, pl.y, right.z, stdv, dir);
    aWY += weightY(pl.x - 1.0, pl.y, right.w, stdv, dir);
    float finalY = aWY.y / aWY.x;
    float maxY = max(max(left.y, left.z), max(right.x, right.w));
    float minY = min(min(left.y, left.z), min(right.x, right.w));
    float deltaY = clamp(uEdgeSharpness * finalY, minY, maxY) - centerDelta;
    deltaY = clamp(deltaY, -23.0 / 255.0, 23.0 / 255.0);
    c = clamp(base + deltaY, 0.0, 1.0);
  }
  float l = luma(c);
  c = mix(vec3(l), c, uSaturation);
  c = (c - 0.5) * uContrast + 0.5 + uBrightness;
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
    }
}
