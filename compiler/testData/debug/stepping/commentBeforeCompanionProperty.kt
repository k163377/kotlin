
// FILE: test.kt

class AWithCompanion {
    companion object {
        //Comment before
        val compPropVal = 1
    }
}

fun box() {
    AWithCompanion.compPropVal
}

// EXPECTATIONS JVM_IR
// test.kt:12 box
// test.kt:7 <clinit>
// test.kt:7 getCompPropVal
// test.kt:7 getCompPropVal
// test.kt:12 box
// test.kt:13 box

// EXPECTATIONS JS_IR
// test.kt:12 box
// test.kt:7 <init>
// test.kt:5 <init>
// test.kt:13 box

// EXPECTATIONS WASM
// test.kt:12 $box (19)
// test.kt:7 $Companion.<init> (26)
// test.kt:8 $Companion.<init> (5)
// test.kt:12 $box (19)
// test.kt:13 $box (1)
