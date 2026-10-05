# iOS prediction strip: visual reference and implementation decisions

Research date: 2026-10-05. The target is the three-word strip above the keys,
not inline gray completion text, keyboard presentation, or key-press bubbles.

## Sources inspected

- [Apple: predictive text](https://support.apple.com/en-au/104995) explains the
  strip and inline predictions separately. It does not specify prediction-strip
  animation curves, durations, or implementation.
- [Apple WWDC23: Keep up with the keyboard](https://developer.apple.com/videos/play/wwdc2023/10281/)
  documents keyboard architecture and inline predictions. The segment around
  14:20 contains a static keyboard example; it cannot establish strip timing.
- [ION HowTo: iPhone prediction demonstration](https://www.youtube.com/watch?v=kWvLX3wXi34&t=8s)
  is a first-hand recording of an iPhone typing in Notes. The creator describes
  it as an iPhone 16 Pro Max with iOS 26 on the
  [accompanying page](https://www.ionhowto.com/predictive-text-suggestions-on-iphone/).
  Frames from approximately 8–10 seconds were inspected at 50 ms intervals.

## Observations from the recording

The strip's dividers stay in place. Updating `T` → `Te` → `Test` produces brief
local blends of old and new letter shapes. The middle/right suggestions also
reshape, including `The` → `Tell` → `Testing` and `This` → `Text` → `Tested`.
Text appears to move around its centered position as its length changes.
There is no obvious consistent 12 dp horizontal sweep of every entire word.

These are visual observations, not confirmation of Apple's internal algorithm.
Camera movement, perspective, compression, and 50 ms sampling prevent exact
measurement of easing, per-letter correspondence, or fade duration. No official
source inspected specifies those details. A screen recording from the precise
iOS version the user likes would be a stronger final calibration reference.

## Decisions for this Android implementation

Keep the row and tap areas fixed. Keep every candidate inside its own slot during
ranking changes: whole-word travel across dividers proved too distracting in
on-device review. Preserve shared
letters throughout the word at full opacity using longest-common-subsequence
correspondence. Animate their individual horizontal positions as the word changes.
New letters glide in by 2 dp; added/removed letters use a subtle 110 ms dissolve.
Use interruptible critically damped springs, without bounce or full-label slide
entrances. These parameter values are our tuning choices,
not measured Apple constants. Keep an animation-off setting and respect the
Android animator scale. Test rapid interruptions and taps during motion.
