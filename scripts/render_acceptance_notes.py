"""Render current acceptance instructions with public exact-source provenance."""
import argparse
from pathlib import Path
import re

def render(source_sha: str, instructions: str) -> str:
    if not re.fullmatch(r'[0-9a-f]{40}', source_sha):
        raise ValueError('Invalid exact source SHA')
    if '0.4.0 / code 20' not in instructions or 'Physical road acceptance: PENDING' not in instructions:
        raise ValueError('Acceptance instructions do not identify the current pending candidate')
    return f'Exact source commit: `{source_sha}`\n\n'+instructions

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--source-sha',required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    instructions=(Path(__file__).resolve().parents[1]/'docs/OWNER_ACCEPTANCE.md').read_text()
    args.output.write_text(render(args.source_sha,instructions),encoding='utf-8')
if __name__=='__main__':
    main()
