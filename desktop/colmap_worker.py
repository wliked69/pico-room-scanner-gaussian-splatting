"""A small COLMAP-compatible CPU worker backed by official pycolmap 3.11.1."""
import argparse
import sys
from pathlib import Path


def main():
    import pycolmap
    parser = argparse.ArgumentParser(description="Room Splat COLMAP CPU worker (pycolmap 3.11.1)")
    parser.add_argument("command", choices=["feature_extractor", "sequential_matcher", "exhaustive_matcher", "mapper", "image_undistorter"])
    parser.add_argument("--database_path")
    parser.add_argument("--image_path")
    parser.add_argument("--input_path")
    parser.add_argument("--output_path")
    parser.add_argument("--output_type", default="COLMAP")
    parser.add_argument("--max_image_size", type=int, default=1600)
    parser.add_argument("--ImageReader.single_camera", type=int, default=1)
    parser.add_argument("--ImageReader.camera_model", default="OPENCV")
    parser.add_argument("--SiftExtraction.use_gpu", type=int, default=0)
    parser.add_argument("--SiftExtraction.max_image_size", type=int, default=1600)
    parser.add_argument("--SiftMatching.use_gpu", type=int, default=0)
    args = parser.parse_args()
    if args.command == "feature_extractor":
        sift = pycolmap.SiftExtractionOptions()
        sift.max_image_size = getattr(args, "SiftExtraction.max_image_size")
        pycolmap.extract_features(args.database_path, args.image_path,
                                 camera_mode=pycolmap.CameraMode.SINGLE,
                                 camera_model=getattr(args, "ImageReader.camera_model"),
                                 sift_options=sift, device=pycolmap.Device.cpu)
    elif args.command in ("sequential_matcher", "exhaustive_matcher"):
        matcher = pycolmap.match_sequential if args.command == "sequential_matcher" else pycolmap.match_exhaustive
        matcher(args.database_path, device=pycolmap.Device.cpu)
    elif args.command == "mapper":
        Path(args.output_path).mkdir(parents=True, exist_ok=True)
        reconstructions = pycolmap.incremental_mapping(args.database_path, args.image_path, args.output_path)
        print(f"Reconstructed {len(reconstructions)} connected models", flush=True)
    elif args.command == "image_undistorter":
        options = pycolmap.UndistortCameraOptions()
        options.max_image_size = args.max_image_size
        pycolmap.undistort_images(args.output_path, args.input_path, args.image_path,
                                 output_type="COLMAP", undistort_options=options)


if __name__ == "__main__":
    main()
