"""Mod-only CPU scheduling; the shared search core and its budgets stay unchanged."""
import os


def worker_limit(requested=0, cpus=None):
    if type(requested) is not int or not 0 <= requested <= 32:
        raise ValueError('search_workers must be an integer from 0 to 32 (0 = auto)')
    if cpus is None:
        counter = getattr(os, 'process_cpu_count', os.cpu_count)
        cpus = counter() or 1
        if hasattr(os, 'sched_getaffinity'):
            cpus = min(cpus, len(os.sched_getaffinity(0)))
    available = max(1, cpus - 2)
    return min(available, requested or min(8, max(1, cpus // 2)))


def background_priority():
    """Lower only this Mod search child, never the game or collection processes."""
    try:
        if os.name == 'nt':
            import ctypes
            kernel = ctypes.WinDLL('kernel32', use_last_error=True)
            kernel.GetCurrentProcess.restype = ctypes.c_void_p
            kernel.SetPriorityClass.argtypes = (ctypes.c_void_p, ctypes.c_uint32)
            if not kernel.SetPriorityClass(kernel.GetCurrentProcess(), 0x4000):
                raise ctypes.WinError(ctypes.get_last_error())
        else:
            os.nice(5)
        return 'below_normal'
    except OSError:
        return 'unchanged'
