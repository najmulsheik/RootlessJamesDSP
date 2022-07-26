package me.timschneeberger.rootlessjamesdsp.model

 class ProcessorMessage {
     enum class Type(val value: Int) {
         Unknown(0),
         VdcParseError(1),
         LiveprogOutput(2),
         LiveprogExec(3),
         LiveprogResult(4);

         companion object {
             fun fromInt(value: Int) = values().first { it.value == value }
         }
     }

     enum class Param(val value: Int) {
         Unknown(0),
         LiveprogFileId(1),
         LiveprogResultCode(2),
         LiveprogErrorMessage(3),
         LiveprogStdout(4);

         companion object {
             fun fromInt(value: Int) = values().first { it.value == value }
         }
     }

     companion object {
         const val TYPE = "type"
     }
}