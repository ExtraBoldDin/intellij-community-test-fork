// "Remove parameter 'a'" "true"
interface OA {
    fun f(a: Int)
}

interface OB {
    fun f(a: Int)
}

interface O : OA, OB {
    override fun f(a: Int)
}

interface OO : O {
    override fun f(a: Int) {
    }
}

interface OOO : OO {
    override fun f(a: Int) {}
}

interface OOOA : OOO {
    override fun f(a: Int) {
    }
}

interface OOOB : OOO {
    override fun f(a: Int) {
    }
}

fun usage(o: OA) {
    o.f(<caret>)
}
fun usage(o: OB) {
    o.f(1)
}

fun usage(o: O) {
    o.f(1)
}

fun usage(o: OO) {
    o.f(13)
}

fun usage(o: OOO) {
    o.f(3)
}

fun usage(o: OOOA) {
    o.f(3)
}

fun usage(o: OOOB) {
    o.f(3)
}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.ChangeFunctionSignatureFix$Companion$RemoveParameterFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.k2.refactoring.changeSignature.quickFix.ChangeSignatureFixFactory$ParameterQuickFix