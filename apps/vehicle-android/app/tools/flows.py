"""Reusable user journeys over the real UI. Screens are reached only through tags a person could press."""
from ui_driver import StepFailed


def go_home(ui):
    for _ in range(8):
        if ui.has('v0.screen'):
            return
        if ui.has('app.back'):
            ui.tap('app.back')
        else:
            ui.force_stop()
            ui.start()
    ui.wait('v0.screen')


def fresh_start(ui):
    ui.force_stop()
    ui.clear_data()
    ui.start()
    try:
        ui.wait('v0.screen')
    except StepFailed:
        # Right after a cold boot the vehicle's navigation app can come up over ours. Only a screen with none of
        # this app's nodes is retried, and only once; a v0 that is really missing still fails on the second wait.
        if ui.find('v0.screen') is not None or any(n.tag.startswith('v') and '.' in n.tag for n in ui.nodes()):
            raise
        print('  note: another app covered the first start after boot; started again', flush=True)
        ui.start()
        ui.wait('v0.screen')


def restart(ui):
    ui.force_stop()
    ui.start()


def add_item(ui, name, x, y, z, error=5, priority=1, rotation=None, stackable=True):
    """Direct entry on the vehicle: typed name, number pad for every dimension, explicit confirmation."""
    ui.tap('v0.library')
    ui.wait('v2.screen')
    ui.tap('v2.new')
    ui.wait('v2.form')
    ui.type_text('v2.name', name)
    ui.keypad_enter('v2.field.X', x)
    ui.keypad_enter('v2.field.Y', y)
    ui.keypad_enter('v2.field.Z', z)
    ui.keypad_enter('v2.field.ERROR', error)
    ui.keypad_enter('v2.field.PRIORITY', priority)
    if rotation is not None:
        ui.tap(f'v2.rotation.{rotation}')
    if not stackable:
        ui.tap('v2.stack.1')
    ui.tap('v2.confirm')
    ui.tap('v2.save')
    ui.wait_scrolled('v2.message')


def create_demo_profile(ui):
    ui.tap('v0.profile')
    ui.wait('v1.screen')
    ui.tap('v1.demo')
    return ui.wait_scrolled('v1.row.0')


def create_measured_profile(ui, x, y, z, opening=None, error=10):
    ui.tap('v0.profile')
    ui.wait('v1.screen')
    ui.tap('v1.measure')
    ui.wait('v1.form')
    ui.keypad_enter('v1.field.X', x)
    ui.keypad_enter('v1.field.Y', y)
    ui.keypad_enter('v1.field.Z', z)
    if opening:
        ui.keypad_enter('v1.field.OPENING_X', opening[0])
        ui.keypad_enter('v1.field.OPENING_Y', opening[1])
    ui.keypad_enter('v1.field.ERROR', error)
    ui.tap('v1.save')
    return ui.wait_scrolled('v1.row.0')


def open_scene(ui, index=0):
    go_home(ui)
    ui.tap(f'v0.scene.{index}.open')
    return ui.wait('v4.screen')


def latest_seeded_scene_index(ui):
    """Rows follow storage order; the seeded scene for a single-seed test is the only row after a clean start."""
    return 0


def search_and_place(ui, library_index):
    ui.tap(f'v4.add.{library_index}.search')
    ui.wait_scrolled('v4.offer', 60)
    ui.tap('v4.offer.accept')
    ui.wait_scrolled('v4.selected')


def confirm_and_save(ui, acknowledge=False):
    wait_enabled(ui, 'v4.confirm')
    ui.tap('v4.confirm')
    wait_enabled(ui, 'v4.save')
    ui.tap('v4.save')
    return ui.wait_scrolled('v4.saved', 30)


def wait_enabled(ui, tag, timeout=30):
    import time
    deadline = time.time() + timeout
    while time.time() < deadline:
        node = ui.scroll_to(tag)
        if node.enabled:
            return node
        time.sleep(0.5)
    raise AssertionError(f'{tag} stayed disabled')
