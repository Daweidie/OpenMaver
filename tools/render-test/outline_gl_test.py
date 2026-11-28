#!/usr/bin/env python3
"""Run the production GLSL on Mesa's surfaceless EGL context, with a pixel oracle.

Uses the standard library and the installed libEGL/libGL; no display server or
Minecraft instance is required. The generated preview is a synthetic mask fixture.
"""
import ctypes as c
import json
import os
from pathlib import Path
import re
import struct
import zlib

os.environ["LIBGL_ALWAYS_SOFTWARE"] = "1"
os.environ["EGL_PLATFORM"] = "surfaceless"
repo = Path(__file__).resolve().parents[2]
egl = c.CDLL("libEGL.so.1")
gl = c.CDLL("libGL.so.1")
I, U, F, P = c.c_int, c.c_uint, c.c_float, c.c_void_p


def bind(lib, name, result, *args):
    fn = getattr(lib, name)
    fn.restype, fn.argtypes = result, list(args)
    return fn


get_display = bind(egl, "eglGetDisplay", P, P)
initialize = bind(egl, "eglInitialize", U, P, c.POINTER(I), c.POINTER(I))
choose_config = bind(egl, "eglChooseConfig", U, P, c.POINTER(I), c.POINTER(P), I, c.POINTER(I))
bind_api = bind(egl, "eglBindAPI", U, U)
create_surface = bind(egl, "eglCreatePbufferSurface", P, P, P, c.POINTER(I))
create_context = bind(egl, "eglCreateContext", P, P, P, P, c.POINTER(I))
make_current = bind(egl, "eglMakeCurrent", U, P, P, P, P)
display = get_display(None)
major, minor = I(), I()
assert initialize(display, c.byref(major), c.byref(minor)), "EGL surfaceless initialization failed"
assert bind_api(0x30A2), "EGL OpenGL API missing"
attributes = (I * 17)(0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3023, 8,
                          0x3022, 8, 0x3021, 8, 0x3025, 24, 0x3038, 0, 0)
config, count = P(), I()
assert choose_config(display, attributes, c.byref(config), 1, c.byref(count)) and count.value
surface = create_surface(display, config, (I * 5)(0x3057, 80, 0x3056, 80, 0x3038))
context = create_context(display, config, None, (I * 1)(0x3038))
assert surface and context and make_current(display, surface, surface, context), "EGL context creation failed"

get_string = bind(gl, "glGetString", c.c_char_p, U)
create_shader = bind(gl, "glCreateShader", U, U)
shader_source = bind(gl, "glShaderSource", None, U, I, c.POINTER(c.c_char_p), c.POINTER(I))
compile_shader = bind(gl, "glCompileShader", None, U)
get_shader = bind(gl, "glGetShaderiv", None, U, U, c.POINTER(I))
shader_log = bind(gl, "glGetShaderInfoLog", None, U, I, c.POINTER(I), P)
create_program = bind(gl, "glCreateProgram", U)
attach_shader = bind(gl, "glAttachShader", None, U, U)
link_program = bind(gl, "glLinkProgram", None, U)
get_program = bind(gl, "glGetProgramiv", None, U, U, c.POINTER(I))
use_program = bind(gl, "glUseProgram", None, U)
uniform = bind(gl, "glGetUniformLocation", I, U, c.c_char_p)
uniform_i = bind(gl, "glUniform1i", None, I, I)
uniform_f = bind(gl, "glUniform1f", None, I, F)
uniform_2f = bind(gl, "glUniform2f", None, I, F, F)
uniform_4f = bind(gl, "glUniform4f", None, I, F, F, F, F)
gen_textures = bind(gl, "glGenTextures", None, I, c.POINTER(U))
bind_texture = bind(gl, "glBindTexture", None, U, U)
tex_parameter = bind(gl, "glTexParameteri", None, U, U, I)
tex_image = bind(gl, "glTexImage2D", None, U, I, I, I, I, I, U, U, P)
gen_fbos = bind(gl, "glGenFramebuffersEXT", None, I, c.POINTER(U))
bind_fbo = bind(gl, "glBindFramebufferEXT", None, U, U)
attach_texture = bind(gl, "glFramebufferTexture2DEXT", None, U, U, U, U, I)
check_fbo = bind(gl, "glCheckFramebufferStatusEXT", U, U)
gen_rbos = bind(gl, "glGenRenderbuffersEXT", None, I, c.POINTER(U))
bind_rbo = bind(gl, "glBindRenderbufferEXT", None, U, U)
storage_rbo = bind(gl, "glRenderbufferStorageEXT", None, U, U, I, I)
attach_rbo = bind(gl, "glFramebufferRenderbufferEXT", None, U, U, U, U)
viewport = bind(gl, "glViewport", None, I, I, I, I)
disable = bind(gl, "glDisable", None, U)
enable = bind(gl, "glEnable", None, U)
depth_mask = bind(gl, "glDepthMask", None, c.c_ubyte)
clear_depth = bind(gl, "glClearDepth", None, c.c_double)
clear = bind(gl, "glClear", None, U)
blend_func = bind(gl, "glBlendFunc", None, U, U)
begin = bind(gl, "glBegin", None, U)
end = bind(gl, "glEnd", None)
uv = bind(gl, "glTexCoord2f", None, F, F)
vertex = bind(gl, "glVertex2f", None, F, F)
read_pixels = bind(gl, "glReadPixels", None, I, I, I, I, U, U, P)
get_error = bind(gl, "glGetError", U)

source = (repo / "mc/PlayerEspOutline.java").read_text()


def shader(name, kind):
    body = re.search(r"private static final String " + name + r" =\n(.*?);\n", source, re.S)[1]
    text = "".join(json.loads(s) for s in re.findall(r'"(?:\\.|[^"\\])*"', body))
    shader_id = create_shader(kind)
    shader_source(shader_id, 1, (c.c_char_p * 1)(text.encode()), None)
    compile_shader(shader_id)
    ok = I()
    get_shader(shader_id, 0x8B81, c.byref(ok))
    if not ok.value:
        log = c.create_string_buffer(4096)
        shader_log(shader_id, 4096, None, log)
        raise AssertionError(log.value.decode())
    return shader_id


program = create_program()
attach_shader(program, shader("VERTEX_SHADER", 0x8B31))
attach_shader(program, shader("FRAGMENT_SHADER", 0x8B30))
link_program(program)
linked = I()
get_program(program, 0x8B82, c.byref(linked))
assert linked.value, "Production outline program failed to link"
use_program(program)
locations = {key: uniform(program, key.encode()) for key in ("uTex", "uStep", "uRadius", "uStage", "uColor")}
assert min(locations.values()) >= 0
uniform_i(locations["uTex"], 0)

W = H = 80
# Head, torso, bent arms and separated legs; the gaps must remain unfilled.
rectangles = [(31, 53, 49, 71), (30, 29, 50, 53), (19, 38, 30, 49),
              (23, 29, 31, 41), (50, 26, 59, 50), (30, 8, 38, 29), (42, 8, 50, 29)]
mask = [int(any(x1 <= x < x2 and y1 <= y < y2 for x1, y1, x2, y2 in rectangles))
        for y in range(H) for x in range(W)]
mask_rgba = bytes(v for filled in mask for v in (20, 40, 180, 255 if filled else 0))
base_rgba = bytes(v for filled in mask for v in ((30, 50, 155, 255) if filled else (150, 137, 87, 255)))


def texture(data=None):
    tex = U()
    gen_textures(1, c.byref(tex))
    bind_texture(0x0DE1, tex.value)
    for key, value in [(0x2801, 0x2600), (0x2800, 0x2600), (0x2802, 0x812F), (0x2803, 0x812F)]:
        tex_parameter(0x0DE1, key, value)
    buffer = c.create_string_buffer(data) if data else None
    tex_image(0x0DE1, 0, 0x1908, W, H, 0, 0x1908, 0x1401, buffer)
    return tex.value


def fbo(tex):
    result = U()
    gen_fbos(1, c.byref(result))
    bind_fbo(0x8D40, result.value)
    attach_texture(0x8D40, 0x8CE0, 0x0DE1, tex, 0)
    assert check_fbo(0x8D40) == 0x8CD5
    return result.value


mask_tex = texture(mask_rgba)
horizontal_tex = texture()
horizontal_fbo = fbo(horizontal_tex)
output_tex = texture()
output_fbo = fbo(output_tex)
depth_buffer = U()
gen_rbos(1, c.byref(depth_buffer))
bind_rbo(0x8D41, depth_buffer.value)
storage_rbo(0x8D41, 0x81A6, W, H)
attach_rbo(0x8D40, 0x8D00, 0x8D41, depth_buffer.value)
assert check_fbo(0x8D40) == 0x8CD5
viewport(0, 0, W, H)
disable(0x0B71)  # depth test
disable(0x0BE2)  # blend
disable(0x0BC0)  # alpha test


def quad():
    begin(7)
    for u, v, x, y in [(0, 0, -1, -1), (1, 0, 1, -1), (1, 1, 1, 1), (0, 1, -1, 1)]:
        uv(u, v)
        vertex(x, y)
    end()


def draw(radius, opacity):
    uniform_f(locations["uRadius"], radius)
    uniform_4f(locations["uColor"], 1, 1, 1, opacity)
    bind_fbo(0x8D40, horizontal_fbo)
    bind_texture(0x0DE1, mask_tex)
    uniform_i(locations["uStage"], 0)
    uniform_2f(locations["uStep"], 1 / W, 0)
    quad()
    bind_fbo(0x8D40, output_fbo)
    bind_texture(0x0DE1, horizontal_tex)
    uniform_i(locations["uStage"], 1)
    uniform_2f(locations["uStep"], 0, 1 / H)
    quad()
    pixels = (c.c_ubyte * (W * H * 4))()
    read_pixels(0, 0, W, H, 0x1908, 0x1401, pixels)
    return bytes(pixels)


checks = 0
for radius, opacity in [(.5, 1), (1, 1), (1.5, .9), (2.3, .65), (4, .2), (4, 1)]:
    actual = draw(radius, opacity)
    weights = [max(0, min(1, radius + 1 - abs(i))) for i in range(-4, 5)]
    horizontal = [round(max(mask[y * W + max(0, min(W - 1, x + i))] * weight
                            for i, weight in zip(range(-4, 5), weights)) * 255) / 255
                  for y in range(H) for x in range(W)]
    for y in range(H):
        for x in range(W):
            expanded = max(horizontal[max(0, min(H - 1, y + i)) * W + x] * weight
                           for i, weight in zip(range(-4, 5), weights))
            expected = round(max(0, expanded - mask[y * W + x]) * opacity * 255)
            assert abs(actual[(y * W + x) * 4 + 3] - expected) <= 2, (radius, opacity, x, y, expected)
            checks += 1

# Composite onto a textured scene and verify its depth and model interiors survive.
bind_texture(0x0DE1, output_tex)
tex_image(0x0DE1, 0, 0x1908, W, H, 0, 0x1908, 0x1401, c.create_string_buffer(base_rgba))
bind_fbo(0x8D40, output_fbo)
depth_mask(1)
clear_depth(.375)
clear(0x100)
before = (F * (W * H))()
read_pixels(0, 0, W, H, 0x1902, 0x1406, before)
depth_mask(0)
enable(0x0BE2)
blend_func(0x0302, 0x0303)
# The horizontal pass writes all RGBA channels and must run with blending disabled.
disable(0x0BE2)
draw(1.5, 1)
# Restore the scene after the unblended oracle pass, then composite only stage 1.
bind_texture(0x0DE1, output_tex)
tex_image(0x0DE1, 0, 0x1908, W, H, 0, 0x1908, 0x1401, c.create_string_buffer(base_rgba))
bind_texture(0x0DE1, horizontal_tex)
enable(0x0BE2)
quad()
pixels = (c.c_ubyte * (W * H * 4))()
read_pixels(0, 0, W, H, 0x1908, 0x1401, pixels)
after = (F * (W * H))()
read_pixels(0, 0, W, H, 0x1902, 0x1406, after)
assert bytes(before) == bytes(after), "Outline damaged the world depth buffer"
for i, filled in enumerate(mask):
    if filled:
        assert bytes(pixels[i * 4:i * 4 + 3]) == base_rgba[i * 4:i * 4 + 3], "Model interior was filled"
        checks += 1
assert bytes(pixels[(40 * W + 18) * 4:(40 * W + 18) * 4 + 3]) == bytes([255] * 3)
assert get_error() == 0, "OpenGL error during the shader test"

# A small standalone PNG makes the hollow contour fixture reviewable.
def png_chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)


scale = 4
rows = []
for y in reversed(range(H)):
    row = b"".join(bytes(pixels[(y * W + x) * 4:(y * W + x) * 4 + 3]) * scale for x in range(W))
    rows.extend([b"\0" + row] * scale)
png = b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", struct.pack(">IIBBBBB", W * scale, H * scale, 8, 2, 0, 0, 0))
png += png_chunk(b"IDAT", zlib.compress(b"".join(rows))) + png_chunk(b"IEND", b"")
(repo / "build/esp-outline-preview.png").write_bytes(png)
print(f"Player ESP GLSL pixel checks passed: {checks}")
print("OpenGL renderer: " + get_string(0x1F01).decode())
make_current(display, None, None, None)
bind(egl, "eglDestroyContext", U, P, P)(display, context)
bind(egl, "eglDestroySurface", U, P, P)(display, surface)
bind(egl, "eglTerminate", U, P)(display)
