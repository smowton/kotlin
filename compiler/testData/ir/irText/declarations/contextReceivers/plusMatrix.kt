// FIR_IDENTICAL
// !LANGUAGE: +ContextReceivers
// !DIAGNOSTICS: -UNUSED_PARAMETER
// IGNORE_BACKEND_K2: JS_IR
// IGNORE_BACKEND_K2: JS_IR_ES6

interface NumberOperations {
    fun Number.plus(other: Number): Number
}

class Matrix

context(NumberOperations) fun Matrix.plus(other: Matrix): Matrix = TODO()

fun NumberOperations.plusMatrix(m1: Matrix, m2: Matrix) {
    m1.plus(m2)
    m2.plus(m1)
}
