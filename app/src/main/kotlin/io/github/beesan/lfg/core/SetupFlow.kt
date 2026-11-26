// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.core

/**
 * Set-up, one question to a screen. Which screens there are depends on what
 * has been answered and what the device already has, so nobody is asked what
 * LFG can see for itself: a handheld with its controller built in is never
 * told to connect one, and a device with everything downloaded never waits.
 */
enum class SetupStep {
    LANGUAGE, READING, CONTROLLER, CONNECT, BUTTON, ANKI, WHERE, AI, IMPROVE, DOWNLOAD, TUTORIAL;

    companion object {
        /** The step saved under [name]; the first for none, or for one this build does not know. */
        fun of(name: String): SetupStep = entries.firstOrNull { it.name == name } ?: LANGUAGE
    }
}

object SetupFlow {
    /**
     * The steps there are, in order. [controller] is the answer to "do you play with a controller?",
     * [padConnected] whether one is there now, [downloaded] whether the dictionary and the reader are in.
     */
    fun steps(controller: Boolean, padConnected: Boolean, downloaded: Boolean): List<SetupStep> = buildList {
        add(SetupStep.LANGUAGE)
        add(SetupStep.READING)
        add(SetupStep.CONTROLLER)
        if (controller) {
            if (!padConnected) add(SetupStep.CONNECT)
            add(SetupStep.BUTTON)
        }
        add(SetupStep.ANKI)
        add(SetupStep.WHERE)
        add(SetupStep.AI)
        add(SetupStep.IMPROVE)
        if (!downloaded) add(SetupStep.DOWNLOAD)
        add(SetupStep.TUTORIAL)
    }

    /** [step] if it is one of [steps]; else the next that is: a controller connected while "Connect it" showed moves on to its button. */
    fun settle(step: SetupStep, steps: List<SetupStep>): SetupStep = steps.firstOrNull { it.ordinal >= step.ordinal } ?: steps.last()

    fun next(step: SetupStep, steps: List<SetupStep>): SetupStep = steps.firstOrNull { it.ordinal > step.ordinal } ?: steps.last()

    /** The step before, or null on the first. */
    fun previous(step: SetupStep, steps: List<SetupStep>): SetupStep? = steps.lastOrNull { it.ordinal < step.ordinal }
}
