// Intentionally empty: every module declares its own plugins (including
// ktlint/detekt) so each build-script classloader has a complete, consistent
// plugin set. Sharing plugin classpaths at the root breaks one way or the
// other: ktlint at root can't see Kotlin, and kotlin.android at root can't
// see AGP (which must stay off the root classpath for -PjvmOnly builds in
// environments without Google Maven access).
