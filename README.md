# AcqEngJ
Java-based acquisition engine for Micro-manager. Used by [Micro-Magellan](https://micro-manager.org/wiki/MicroMagellan), [Pycro-Manager](https://pycro-manager.readthedocs.io/en/latest/), [LightSheetManager](https://github.com/micro-manager/LightSheetManager), and (optionally) Micro-Manager

Open an issue if you'd like to learn more.


### Creating acquisition engine
```
import Engine;

...
//Get Micro-manager core
...

Engine eng = new Engine(core);
```


