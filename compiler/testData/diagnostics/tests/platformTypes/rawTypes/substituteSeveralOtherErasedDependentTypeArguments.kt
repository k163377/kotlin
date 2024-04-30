// WITH_STDLIB
// FULL_JDK

// FILE: X.java
class X<B extends I<P>, P, A extends I<B>> {
    static final E<X> E = new E<>();

    String getId() {
        return null;
    }
}

// FILE: E.java
class E<T> {
    T getT() {
        return null;
    }
}

// FILE: I.java
interface I<P> {}

// FILE: test.kt
fun test() {
    val t = X.E.t
    <!DEBUG_INFO_EXPRESSION_TYPE("(X<(I<(kotlin.Any..kotlin.Any?)>..I<(kotlin.Any..kotlin.Any?)>?), (kotlin.Any..kotlin.Any?), (I<(I<(kotlin.Any..kotlin.Any?)>..I<(kotlin.Any..kotlin.Any?)>?)>..I<(I<(kotlin.Any..kotlin.Any?)>..I<(kotlin.Any..kotlin.Any?)>?)>?)>..X<out (I<*>..I<*>?), *, out (I<out (I<*>..I<*>?)>..I<out (I<*>..I<*>?)>?)>?)")!>t<!>
    t.id // should be OK
}
