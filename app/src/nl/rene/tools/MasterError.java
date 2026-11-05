package nl.rene.tools;

/** Alleen vaste, vertaalbare foutmeldingen mogen de gegevenskluis verlaten. */
final class MasterError extends Exception {
    MasterError(String message){super(message);}
}
