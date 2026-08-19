'''
Entry point spec for tetron-mobile-sync.

libspec auto-discovery compiles spec/main_spec.py first; this Spec pulls in
the requirement/constraint classes defined in spec/sync.py.
'''

from libspec import Spec
from . import sync


class SyncSpec(Spec):

  def modules(self):
    return [
        sync,
    ]