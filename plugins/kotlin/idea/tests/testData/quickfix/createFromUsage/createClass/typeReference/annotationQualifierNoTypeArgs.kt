// "Create annotation 'A'" "false"
// ERROR: Unresolved reference: A
// K2_AFTER_ERROR: Unresolved reference 'A'.
package p

internal fun foo(): <caret>A.B = throw Throwable("")