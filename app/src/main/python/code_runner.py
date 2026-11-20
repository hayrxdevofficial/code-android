"""Execution bridge for CODE's embedded Python runtime."""

import contextlib
import io
import traceback


def run_code(source: str, filename: str = "main.py") -> str:
    """Execute one editor buffer and return stdout, stderr and traceback text."""
    output = io.StringIO()
    namespace = {
        "__name__": "__main__",
        "__file__": filename,
    }

    try:
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            compiled = compile(source, filename, "exec")
            exec(compiled, namespace, namespace)
    except Exception:
        traceback.print_exc(file=output)

    return output.getvalue()
