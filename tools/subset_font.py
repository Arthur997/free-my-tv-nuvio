"""Cuts Inter (variable) down to what the app shows: Latin (with Vietnamese), Greek and Cyrillic, punctuation, currency,
arrows and math signs, with the layout features the app uses (kerning, ligatures, tabular digits). Japanese, Chinese,
Korean and Hindi aren't in Inter at all (the system font draws them). About 40% smaller than the full font.
Usage: python3 -I subset_font.py InterVariable.ttf OUT.ttf   (needs fonttools: pip install fonttools)"""
import sys

from fontTools import subset

UNICODES = ("U+0020-007E,U+00A0-024F,U+02B0-02FF,U+0300-036F,U+0370-03FF,U+0400-052F,U+1E00-1EFF,U+2000-206F,"
            "U+20A0-20CF,U+2100-218F,U+2190-21FF,U+2200-22FF,U+25A0-25FF,U+FB00-FB06")


def main():
    source, out = sys.argv[1], sys.argv[2]
    subset.main([source, "--output-file=" + out, "--unicodes=" + UNICODES, "--layout-features+=tnum,case",
                 "--layout-features-=aalt,salt,dlig", "--notdef-outline", "--no-hinting"])


if __name__ == "__main__":
    main()
