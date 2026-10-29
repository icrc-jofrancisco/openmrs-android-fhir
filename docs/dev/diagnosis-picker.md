# Diagnosis picker

A form question that lets the user **search a long list of diagnoses and tick several**, saved as one
FHIR encounter diagnosis (`Condition`) per tick. No bundled form uses it yet: a form gets the
question as described below, once the backend takes diagnoses (see "What is saved").

## In a form

```json
{
  "linkId": "diagnoses",
  "type": "choice",
  "repeats": true,
  "required": true,
  "text": "Diagnoses of this visit",
  "extension": [
    {
      "url": "http://hl7.org/fhir/StructureDefinition/questionnaire-itemControl",
      "valueCodeableConcept": {
        "coding": [{"system": "http://fhir.openmrs.org/code-system/questionnaire-item-control",
                    "code": "diagnosis-picker"}]
      }
    },
    {"url": "http://fhir.openmrs.org/ext/diagnosis-catalog", "valueString": "diagnoses"}
  ]
}
```

No `answerOption`: the options come from the CSV named by the `diagnosis-catalog` extension (`diagnoses`
when left out). The answers are `valueCoding`s (the concept uuid, with the name), so the review page
and the validation of required questions work as for any multiple-choice question.

### One question for the primary diagnosis, one for the secondary ones

Add the extension `http://fhir.openmrs.org/ext/diagnosis-picker-rank` (`@o/diagnosis-picker-rank` in
the compact form format) with `valueCode` `primary` or `secondary` to a picker and **everything
ticked in it has that rank**. A form usually has both: a *Primary diagnosis* question (`repeats:
false`, so one diagnosis, and `required`) and a *Secondary diagnoses* one (`repeats: true`).

- A diagnosis ticked in both questions is saved once, as primary.
- A picker with no rank extension keeps the first rule: the first diagnosis ticked is the primary one
  and the others are secondary (a hint under the chips says so; a picker with a rank shows no
  hint). In a form that has a primary question, the diagnoses of an unranked picker are secondary.
- Editing an encounter fills each question with the diagnoses of its rank. A diagnosis moved to the
  other question is replaced: the old condition is dropped and one with the new rank is created.

## The list: a CSV in the build

The app holds the list as a CSV in `assets/diagnoses/<id>.csv`; `diagnoses.csv` in `core` is the
default. A build puts its own file there: a file of the same name in the assets of `app` or
`dev3-app` replaces the one of `core`. There is no other format and no way to load a list at
run time.

```
uuid,name:en,name:fr,name:es,name:ru,name:ar,code
10AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA,Acute bronchitis,Bronchite aiguë,bronquitis aguda,,,J20.9
```

| column | |
|---|---|
| `uuid` | the uuid of the concept: all the server needs (ids differ from one server to another and are not used) |
| `name:<language>` | what the user reads, one column per language (`name:en`, `name:fr`, `name:es`, `name:ru`, `name:ar`; any other language tag works). The name in the language of the app is shown; **a cell left empty falls back to the English name** (`name:en`, else the first language of the file). A column `name` with no language is the default name instead of `name:en`. Every language is searched |
| `code` | an ICD-10 code of the concept (ICD-10-WHO and derivatives): what the user searches by and sees next to the name. May be empty |

The other columns are ignored, in any order. A concept with several codes has one row for each (the
same uuid, the names repeated); its rows become one diagnosis (the first five codes in the order of
the file). The bundled file is sorted by code and has the concepts of the dictionary export that
have an ICD-10-WHO, ICD-10, ICD-10-WHO 2nd, NP or NP2 mapping (42,691 concepts, 45,211 rows, 12.7 MB).
The file is read once, off the main thread, when the form opens and kept in memory; a search takes a
few milliseconds and runs after a typing pause. Every word must be in a code or a name; the exact
code first, then codes that start with the text, names that start with it, words of the name, the
rest; 100 shown with the number of matches. A blank search shows what is ticked, not the whole list.

**Translations.** The French, Spanish, Russian and Arabic names of the bundled file are filled for
every concept. They are machine translated (by a language model, in batches), not taken from any
OpenMRS dictionary. About 1,500 entries that an automatic check found odd (a gloss added in
parentheses, an odd length, a source name that is not English, mixed scripts) were verified and
fixed in a second pass; the rest were checked for shape only, and no clinician reviewed any of them.
A name can be improved in the file with no code change, and a cell left empty shows the English name.

## Loading, memory and recent diagnoses

**Load time.** Reading the CSV is slow (quoted fields, one row per code, 45,000 rows); the result is
stored in the private cache of the app (`cacheDir/diagnosis-list-<id>.bin`, `DiagnosisCatalogCache`)
and the next start reads that. The copy is tagged with the last install/update time of the app, the
only time the CSV can change, so an update makes a new one; a missing, cut or foreign copy is ignored
and the CSV is read (it is a cache, never a source, and logging out with "delete data" removes it).
The search index (lower-cased names without accents, in every language) is built in the background
after the list is loaded; a search made before it is ready waits for it. Measured on the Android 13
x86_64 emulator, in a new process (a phone is typically faster):

| | before | now |
|---|---|---|
| first start after an install (CSV) | 10.4 s | 4.1 s |
| later starts (from the copy), until the list can be used | 3.3 s | 1.2 s |
| search index, in the background | (in the load) | ~2 s |

The list holds about 65 MB of memory (the emulator allows an app 192 MB). `FhirApplication` lets go
of it when the system asks for memory (`onLowMemory`, and `onTrimMemory` at the running low,
running critical and background levels); a form that is open keeps its list until it is closed, and
the next ask reads it again from the copy. The time of each load is in the app log (`Timber.i`).

**Recent diagnoses.** When a new encounter or an edit is saved, the diagnoses it has go to the top of
a short list (12, `RecentDiagnoses`, one uuid per line in `filesDir/diagnosis-recent.txt`, so it
goes with the rest of the data when that is deleted at logout). The search shows it, under what is
ticked, while nothing is typed. It holds no patient data and costs one small file read.

## What is saved

`GenericFormEntryViewModel` (new encounter, group sessions) and `EditEncounterViewModel` (edit) turn
the picked codings into `Condition`s: category `encounter-diagnosis`, patient, encounter,
`recordedDate`, certainty *provisional*, the concept as a coding with no system and the uuid as
code, and the **rank**: `1` (primary) or `2` (secondary), as above. Editing an encounter shows its
diagnoses ticked in the question of their rank; a diagnosis taken out is dropped if it was never
uploaded, else deleted on the server; one added in a picker without a rank is secondary, unless none
of the diagnoses that stay is primary, then the first one added is. A diagnosis the list does not
know, one written as free text and the other conditions of an encounter are left alone.

Deleting an unsynced encounter from the Sync screen takes its conditions with it (they would stay on
the device and be refused by the server, since their encounter was never uploaded).

No call to the server is made for this: the uuids of the CSV are the ones of the server's
dictionary. On OpenMRS (FHIR2 4.3.0, tested on dev3.openmrs.org) the concept of a coding with no
system is found by its uuid, a `Condition` without the `diagnosis-rank` extension is refused (422),
and **a uuid the server does not have is stored without any concept and answered 201**: the CSV has
to come from the dictionary of the server the app talks to.

## Not done

- The tutorial step for the question (the tours name screens, not form questions).
- Certainty is not asked of the user (provisional).
- A diagnosis read back from the server that is free text is not shown in the picker (nor touched).
