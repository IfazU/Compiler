package wacc

/**
  * Constants that are used throughout the program
  */
object globals {
    val exitSuccess     = 0;
    val exitSyntaxErr   = 100;
    val exitSemanticErr = 200;
    val exitInvalidOptions = 2;

    var syntaxCheckOnly = false
    var semanticCheckOnly = false
    var executeAssembly = false
    var acceptInput = false
    var enableColours = true

    var useDocker = false
    var useWSL = false
}