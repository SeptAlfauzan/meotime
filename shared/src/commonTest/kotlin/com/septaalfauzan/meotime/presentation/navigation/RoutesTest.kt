package com.septaalfauzan.meotime.presentation.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The overlay's "Manage restriction" deep link is the only writer of these routes, and a
 * malformed path segment would silently send the user to the wrong app's settings. Package names
 * are `[A-Za-z0-9_.]` in practice, so these assertions pin the *defensive* behaviour: a value that
 * is not a package name still cannot escape its own segment or forge a second one.
 */
class RoutesTest {

    @Test
    fun realPackageNamesPassThroughUnchanged() {
        assertEquals("com.google.android.youtube", encodePathSegment("com.google.android.youtube"))
        assertEquals("com.septaalfauzan.meotime", encodePathSegment("com.septaalfauzan.meotime"))
    }

    @Test
    fun separatorsAndSpacesArePercentEncoded() {
        val encoded = encodePathSegment("com.example/my app?x=1#y")

        assertFalse(encoded.contains('/'), "a slash would create a new route segment: $encoded")
        assertFalse(encoded.contains('?'), "a query marker would truncate the route: $encoded")
        assertFalse(encoded.contains('#'), "a fragment would truncate the route: $encoded")
        assertFalse(encoded.contains(' '), "a space must be percent encoded in a path: $encoded")
        assertTrue(encoded.startsWith("com.example%2Fmy%20app"), encoded)
    }

    @Test
    fun nonAsciiIsEncodedAsUtf8Bytes() {
        // U+00E9 is two UTF-8 bytes (0xC3 0xA9), so the encoder must emit two escapes.
        assertEquals("caf%C3%A9", encodePathSegment("caf\u00e9"))
    }

    @Test
    fun routeHelpersPrefixTheEncodedSegment() {
        assertEquals("appsettings/com.example.app", Routes.appSettings("com.example.app"))
        assertEquals("limit/com.example.app", Routes.limit("com.example.app"))
        assertEquals("appsettings/com.example%2Fodd", Routes.appSettings("com.example/odd"))
    }

    @Test
    fun routeTemplatesStayUnencoded() {
        // The templates are matched by the navigation library; encoding them would break matching.
        assertEquals("appsettings/{packageName}", Routes.APP_SETTINGS)
        assertEquals("limit/{packageName}", Routes.LIMIT)
    }

    @Test
    fun argumentNameMatchesTheTemplatePlaceholder() {
        // A navArgument declared under a different name than the template placeholder resolves to
        // null silently, which is exactly how Task 12/13 would read a package that is missing.
        assertTrue(Routes.APP_SETTINGS.endsWith("{${Routes.ARGUMENT_PACKAGE}}"), Routes.APP_SETTINGS)
        assertTrue(Routes.LIMIT.endsWith("{${Routes.ARGUMENT_PACKAGE}}"), Routes.LIMIT)
    }
}
