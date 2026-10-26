# Contacts + distress ACK Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Saved contacts (name+occupation) in Mesh with online status; 2-min distress rebroadcast to new joiners; first Accept stops blast and ACKs the whole mesh.

**Architecture:** New wire kinds `m9.card` / `m9.ack` on PhoneNode; ContactStore on app; service exchanges cards after verify and drives distress ticks; Mesh UI lists contacts.

**Tech stack:** Kotlin Android, PhoneNode, Nearby, Compose, JUnit.

### Task 1: Wire kinds + PhoneNode card/ack/distress
### Task 2: ContactStore + service + UI
### Task 3: Tests + assembleDebug install
