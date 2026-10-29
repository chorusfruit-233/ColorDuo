#!/usr/bin/env python3
"""Exercise slider summaries with the same Formatter arguments as SliderRow."""
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
summaries = []
for directory in sorted((ROOT / 'app/src/main/res').glob('values*')):
    resource = directory / 'strings.xml'
    if not resource.exists():
        continue
    for string in ET.parse(resource).getroot().findall('string'):
        if string.get('name') in {'sim_stack_gap_summary', 'signal_icon_scale_summary', 'dot_scale_summary'}:
            summaries.append(''.join(string.itertext()))
assert len(summaries) >= 6, 'Check both Chinese and English summaries'

with tempfile.TemporaryDirectory() as tmp:
    source = Path(tmp) / 'SettingsFormatCheck.java'
    source.write_text('''
import java.util.Locale;
import java.util.IllegalFormatException;
class SettingsFormatCheck {
    public static void main(String[] summaries) {
        // Prove the regression detects the original page-opening crash.
        try {
            String.format(Locale.ROOT, "At 0% the rows touch", 50, 0, 300);
            throw new AssertionError("Original summary must fail formatting");
        } catch (IllegalFormatException expected) { }
        for (String summary : summaries) {
            String result = String.format(Locale.ROOT, summary, 50, 0, 300);
            if (result.contains("%%") || !result.contains("%")) {
                throw new AssertionError("Wrong displayed percentage: " + result);
            }
        }
        System.out.println("Settings summaries format without crashing: " + summaries.length);
    }
}
''', encoding='utf-8')
    subprocess.run(['java', str(source), *summaries], check=True)
