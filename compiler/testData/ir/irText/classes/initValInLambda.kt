// FIR_IDENTICAL
// IGNORE_BACKEND_K2: JS_IR
// IGNORE_BACKEND_K2: JS_IR_ES6
class TestInitValInLambdaCalledOnce {
    val x: Int
    init {
        1.run {
            x = 0
        }
    }
}
