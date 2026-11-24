"""Estimate WP-09 manual alignment settings from an exported image pair using MINIMA-LoFTR."""

import argparse
import hashlib
import math
import zipfile
from copy import deepcopy
from pathlib import Path

import cv2
import numpy as np
import torch
from kornia.feature import LoFTR
from kornia.feature.loftr.loftr import default_cfg

WEIGHTS_SHA256 = "810d19773ff898ba04a68c99a3eff9c112210bf884214bd76aec885e83b0e257"


def read_pair(path):
    with zipfile.ZipFile(path) as archive:
        def image(name):
            if archive.getinfo(name).file_size > 8_000_000:
                raise ValueError(f"图像过大: {name}")
            data = np.frombuffer(archive.read(name), dtype=np.uint8)
            result = cv2.imdecode(data, cv2.IMREAD_GRAYSCALE)
            if result is None:
                raise ValueError(f"无法读取 {name}")
            return result

        props = dict(line.split("=", 1) for line in archive.read("pair.properties").decode("ascii").splitlines()
                     if "=" in line and not line.startswith("#"))
        return image("visible.png"), image("thermal.png"), props


def fill_scale(width, height, dest_width, dest_height, angle):
    cos = abs(math.cos(math.radians(angle)))
    sin = abs(math.sin(math.radians(angle)))
    return max((dest_width * cos + dest_height * sin) / width,
               (dest_width * sin + dest_height * cos) / height)


def initial_warp(source, target, angle, zoom, dx, dy):
    h, w = source.shape
    th, tw = target.shape
    scale = fill_scale(w, h, tw, th, angle) * zoom / 100
    center = ((w - 1) / 2, (h - 1) / 2)
    matrix = cv2.getRotationMatrix2D(center, -angle, scale)
    matrix[:, 2] += [(tw - w) / 2 + dx * tw / 100, (th - h) / 2 + dy * th / 100]
    return matrix


def tensor(image):
    return torch.from_numpy(image).float()[None, None] / 255


def estimate(matcher, source, target, initial):
    h, w = target.shape
    warped = cv2.warpAffine(source, initial, (w, h))
    # Keep the same pixel scale on both axes for the robust similarity fit.
    size = (384, max(32, round(384 * h / w / 32) * 32))
    batch = {"image0": tensor(cv2.resize(warped, size)), "image1": tensor(cv2.resize(target, size))}
    with torch.inference_mode():
        matches = matcher(batch)
    to_native = np.array([w / size[0], h / size[1]], dtype=np.float32)
    left = (matches["keypoints0"].numpy() + .5) * to_native - .5
    right = (matches["keypoints1"].numpy() + .5) * to_native - .5
    src = cv2.transform(left[None], cv2.invertAffineTransform(initial))[0] if len(left) else left
    valid = ((src >= 0).all(axis=1) & (src < source.shape[::-1]).all(axis=1)
             & (matches["confidence"].numpy() >= .25))
    left, right = left[valid], right[valid]
    if len(left) < 12:
        return None
    fit, mask = cv2.estimateAffinePartial2D(left, right, method=cv2.RANSAC, ransacReprojThreshold=3.0)
    if fit is None:
        return None
    keep = mask.ravel().astype(bool)
    inliers = right[keep]
    if len(inliers) < 12 or len(inliers) < len(left) * .45:
        return None
    coverage = cv2.contourArea(cv2.convexHull(inliers)) / (w * h)
    if coverage < .12:
        return None
    residuals = np.linalg.norm(cv2.transform(left[keep][None], fit)[0] - inliers, axis=1)
    matrix = fit @ np.vstack([initial, [0, 0, 1]])
    return matrix, len(inliers), len(left), coverage, float(np.median(residuals))


def controls(matrix, source, target, sensor):
    h, w = source.shape
    th, tw = target.shape
    angle = math.degrees(math.atan2(matrix[1, 0], matrix[0, 0]))
    rotation = round(angle - sensor) % 360
    scale = math.hypot(matrix[1, 0], matrix[0, 0])
    zoom = round(100 * scale / fill_scale(w, h, tw, th, rotation + sensor))
    center = matrix @ np.array([(w - 1) / 2, (h - 1) / 2, 1])
    dx, dy = np.rint(100 * (center - [(tw - 1) / 2, (th - 1) / 2]) / [tw, th]).astype(int)
    return zoom, dx, dy, rotation


def load_matcher(weights):
    if hashlib.sha256(weights.read_bytes()).hexdigest() != WEIGHTS_SHA256:
        raise ValueError("权重与 MINIMA 作者发布的 minima_loftr.ckpt 不符")
    torch.set_num_threads(min(torch.get_num_threads(), 4))
    config = deepcopy(default_cfg)
    config["coarse"]["temp_bug_fix"] = True
    matcher = LoFTR(pretrained=None, config=config).eval()
    state = torch.load(weights, map_location="cpu", weights_only=True)["state_dict"]
    matcher.load_state_dict({k.removeprefix("matcher."): v for k, v in state.items() if k.startswith("matcher.")})
    return matcher


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pair", type=Path)
    parser.add_argument("--weights", type=Path, required=True, help="Official minima_loftr.ckpt")
    parser.add_argument("--preview", type=Path, help="保存应用取整参数后的对齐预览")
    args = parser.parse_args()
    source, target, props = read_pair(args.pair)
    matcher = load_matcher(args.weights)
    sensor = int(props["sensor_orientation"])
    manual = tuple(int(props[key]) for key in ("manual_zoom", "manual_x", "manual_y", "manual_rotation"))
    candidates = [manual] + [(100, 0, 0, angle) for angle in (0, 90, 180, 270)]
    for seed in dict.fromkeys(candidates):
        zoom, dx, dy, rotation = seed
        result = estimate(matcher, source, target, initial_warp(source, target, sensor + rotation, zoom, dx, dy))
        if result is None:
            continue
        matrix, count, total, coverage, error = result
        zoom, dx, dy, rotation = controls(matrix, source, target, sensor)
        if not (20 <= zoom <= 1200 and abs(dx) <= 400 and abs(dy) <= 400):
            continue
        print(f"相机缩放 {zoom}%  水平偏移 {dx}%  垂直偏移 {dy}%  角度 {rotation}°")
        print(f"几何内点 {count}/{total}，覆盖热像 {coverage:.0%}，内点中位拟合残差 {error:.2f} 热像像素")
        if args.preview:
            warp = initial_warp(source, target, sensor + rotation, zoom, dx, dy)
            blended = cv2.warpAffine(source, warp, target.shape[::-1])
            combined = cv2.merge([blended, target, blended])
            if not cv2.imwrite(str(args.preview), combined):
                raise OSError(f"无法保存预览 {args.preview}")
        return
    parser.exit(1, "本图对缺少分布足够广的一致匹配，未给出对齐参数。\n")


if __name__ == "__main__":
    main()
