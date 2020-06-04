# AcqEngJ
Java-based acquisition engine for Micro-manager. Used by [Micro-Magellan](https://micro-manager.org/wiki/MicroMagellan) and [Pycro-Manager](https://pycro-manager.readthedocs.io/en/latest/). Open an issue if you'd like to learn more.


### Creating acquisition engine
```
import org.micromanager.acqj.internal.acqengj.Engine;

...
//Get Micro-manager core
...

Engine eng = new Engine(core);
```


