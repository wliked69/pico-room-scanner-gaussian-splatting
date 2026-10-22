import argparse
import json
from pathlib import Path

from .capture import import_archive, validate_capture
from .pipeline import Pipeline, Settings


def main():
    parser = argparse.ArgumentParser(description="Room Splat 房间高斯重建")
    sub = parser.add_subparsers(dest="command")
    check = sub.add_parser("validate")
    check.add_argument("capture", type=Path)
    ingest = sub.add_parser("import")
    ingest.add_argument("archive", type=Path)
    ingest.add_argument("destination", type=Path)
    train = sub.add_parser("reconstruct")
    train.add_argument("capture", type=Path)
    train.add_argument("--colmap", required=True)
    train.add_argument("--brush", required=True)
    train.add_argument("--iterations", type=int, default=7000)
    train.add_argument("--exhaustive", action="store_true")
    train.add_argument('--no-collision',action='store_true')
    train.add_argument('--collision-quality',choices=('快速','标准','精细'),default='标准')
    mesh=sub.add_parser('mesh',help='从已有 PLY 导出碰撞 OBJ')
    mesh.add_argument('source',type=Path)
    mesh.add_argument('--output',type=Path)
    mesh.add_argument('--quality',choices=('快速','标准','精细'),default='标准')
    mesh.add_argument('--status-file',type=Path)
    args = parser.parse_args()
    if args.command == "validate":
        manifest = validate_capture(args.capture)
        print(json.dumps({"schema": manifest["schema"], "frames": len(manifest["frames"]), "valid": True}))
    elif args.command == "import":
        manifest = import_archive(args.archive, args.destination)
        print(f"导入 {len(manifest['frames'])} 张图像：{args.destination}")
    elif args.command == "reconstruct":
        print(Pipeline().reconstruct(args.capture, Settings(args.colmap, args.brush, args.iterations, exhaustive=args.exhaustive,collision=not args.no_collision,collision_quality=args.collision_quality)))
    elif args.command=='mesh':
        from .collision import export_collision
        import sys
        try:
            result=export_collision(args.source,parent=args.output,quality=args.quality,log=print if sys.stdout else lambda _:None)
            if args.status_file: args.status_file.write_text(json.dumps(dict(passed=True,obj=str(result))),encoding='utf-8')
        except Exception as error:
            if args.status_file: args.status_file.write_text(json.dumps(dict(passed=False,error=str(error))),encoding='utf-8')
            raise SystemExit(2)
    else:
        from .gui import main as gui
        gui()


if __name__ == "__main__":
    main()
