"""Export MINIMA-LoFTR's coarse matching stage for one-shot WP-09 registration."""

import argparse
from pathlib import Path

import torch

from align_fusion_pair import load_matcher


class MobileMatches(torch.nn.Module):
    def __init__(self, matcher):
        super().__init__()
        self.matcher = matcher

    def forward(self, images):
        coarse, _ = self.matcher.backbone(images)
        features = self.matcher.pos_encoding(coarse).flatten(2).transpose(1, 2)
        left, right = self.matcher.loftr_coarse(features[:1], features[1:])
        similarity = (left / 16) @ (right / 16).transpose(1, 2)
        similarity = similarity / self.matcher.coarse_matching.temperature
        confidence = similarity.softmax(1) * similarity.softmax(2)
        scores, target = confidence[0].max(dim=1)
        reverse = confidence[0].argmax(dim=0)
        mutual = reverse[target] == torch.arange(scores.shape[0], device=scores.device)
        return torch.stack([target.float(), scores * mutual], dim=1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("weights", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    model = MobileMatches(load_matcher(args.weights)).eval()
    with torch.inference_mode():
        torch.onnx.export(model, torch.zeros(2, 1, 192, 256), args.output,
                          input_names=["images"], output_names=["matches"], opset_version=17)
