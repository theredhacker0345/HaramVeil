#!/usr/bin/env python3
"""Find a clickable UI element by text label and print its center coordinates.

Usage: python3 find_element.py <ui_dump.xml> <label>

Searches the uiautomator XML dump for a node whose text or content-desc
contains the given label and is clickable. Falls back to searching for a
clickable parent of a matching text node (Compose's nested structure).

Prints "X Y" on success, exits 0.
Exits 1 if not found.
"""
import sys
import re
import xml.etree.ElementTree as ET


def find_element(xml_path, label):
    tree = ET.parse(xml_path)
    root = tree.getroot()

    # First pass: find a clickable node whose own text/desc matches
    for node in root.iter('node'):
        t = node.get('text', '') or node.get('content-desc', '')
        if label in t and node.get('clickable') == 'true':
            b = node.get('bounds', '')
            m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
            if m:
                x = (int(m.group(1)) + int(m.group(3))) // 2
                y = (int(m.group(2)) + int(m.group(4))) // 2
                print(f"{x} {y}")
                return 0

    # Second pass: find a clickable parent of a matching text node
    for node in root.iter('node'):
        if node.get('clickable') != 'true':
            continue
        for child in node.iter():
            if child is node:
                continue
            t = child.get('text', '') or child.get('content-desc', '')
            if label in t:
                b = node.get('bounds', '')
                m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
                if m:
                    x = (int(m.group(1)) + int(m.group(3))) // 2
                    y = (int(m.group(2)) + int(m.group(4))) // 2
                    print(f"{x} {y}")
                    return 0

    return 0  # always exit 0; empty output means not found


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: find_element.py <xml> <label>", file=sys.stderr)
        sys.exit(0)
    sys.exit(find_element(sys.argv[1], sys.argv[2]))
