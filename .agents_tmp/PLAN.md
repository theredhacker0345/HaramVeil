# 1. OBJECTIVE

**Problem**: The app is not blocking adult content on screen as expected.

**Goal**: Identify why the detection pipeline fails to show the blocking veil when adult content appears on screen, and fix the issue.

# 2. CONTEXT SUMMARY

**System Overview**: HaramVeil is an Android app that detects and blocks adult content using a 3-mode detection pipeline:
- **Mode 1**: UI tree scanning (keywords/domains in accessibility nodes)
- **Mode 2**: OCR-based text detection from screenshots  
- **Mode 3**: Visual AI model detection (NudeNet)

When adult content is detected, the app should show a full-screen "veil" overlay and force-close the app.

**Relevant Files**:
- `HaramVeilAccessibilityService.kt` - Main detection logic (lines 271-295)
- `ProtectionPreferencesRepository.kt` - Settings with default blocklists
- `VeilOverlayService.kt` - The blocking overlay UI

# 3. APPROACH OVERVIEW

I've discovered a critical logic flaw in the detection pipeline. The code incorrectly handles **MEDIUM risk content detection**:

**THE BUG**: In `HaramVeilAccessibilityService.kt`, lines 281-291:

```kotlin
RiskLevel.MEDIUM -> {
    // For MEDIUM risk: show veil if mode2/mode3 are disabled, otherwise escalate to Mode 2/3
    if (!currentSettings.mode2Enabled && !currentSettings.mode3Enabled) {
        DetectionBus.publishVeilRequested(...)
    }
}
```

**Problem**: This logic only shows the veil if BOTH Mode 2 AND Mode 3 are disabled. If EITHER is enabled, the veil is NOT shown - it only escalates without blocking. 

**Root Cause Analysis**:
1. Most users have Mode 2 enabled (OCR text detection)
2. Mode 3 is enabled by default in `ProtectionPreferencesRepository.kt` line 55 (though defaults to false in ProtectionSettings model - a discrepancy)
3. When a MEDIUM-risk app (like Instagram, TikTok, YouTube) is opened, the app checks for adult content but waits for Mode 2/3 OCR/visual detection instead of immediately showing the veil
4. This creates a race condition/timing gap where adult content appears on screen but no blocking veil is shown

**Impact**: For all MEDIUM-risk apps (browsers, Instagram, TikTok, etc.), the immediate veil block is BROKEN unless BOTH Mode 2 and Mode 3 are disabled.

# 4. IMPLEMENTATION STEPS

## Step 1: Fix the MEDIUM risk blocking logic
**Goal**: Show immediate veil for detected adult content regardless of Mode 2/3 status.
**Method**: Modify `HaramVeilAccessibilityService.kt` around lines 281-291 to ALWAYS publish veil for MEDIUM risk, not just when both modes are disabled.
**Reference**: `HaramVeilAccessibilityService.kt:281-295`

**Current problematic code**:
```kotlin
RiskLevel.MEDIUM -> {
    if (!currentSettings.mode2Enabled && !currentSettings.mode3Enabled) {
        DetectionBus.publishVeilRequested(...)
    }
}
```

**Fix**: Change to always publish veil for MEDIUM risk:
```kotlin
RiskLevel.MEDIUM -> {
    // Always show veil for medium-risk detection
    DetectionBus.publishVeilRequested(...)
}
```

## Step 2: Verify ProtectionSettings consistency  
**Goal**: Ensure default settings are consistent between model and repository.
**Method**: Verify `mode3Enabled` default is correctly handled (currently defaults to `true` in repository but `false` in model - should be clarified).
**Reference**: `ProtectionPreferencesRepository.kt:55` vs `ProtectionSettings.kt:27`

# 5. TESTING AND VALIDATION

**How to verify the fix works**:
1. Open a MEDIUM-risk app (Instagram, Chrome, YouTube)
2. Trigger a test detection with keyword from blocklist (or use Test Detection screen)
3. Confirm the veil overlay appears IMMEDIATELY
4. Check that lockdown is applied and app is force-closed

**Expected behavior after fix**:
- When adult content keywords are detected in ANY app (HIGH or MEDIUM risk), the veil should appear IMMEDIATELY
- Mode 2/3 can still run for additional detection but should NOT block the immediate veil response
