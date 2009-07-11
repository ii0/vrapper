package net.sourceforge.vrapper.vim.modes;

import static net.sourceforge.vrapper.keymap.StateUtils.union;
import static net.sourceforge.vrapper.keymap.vim.ConstructorWrappers.changeCaret;
import static net.sourceforge.vrapper.keymap.vim.ConstructorWrappers.convertKeyStroke;
import static net.sourceforge.vrapper.keymap.vim.ConstructorWrappers.leafBind;
import static net.sourceforge.vrapper.keymap.vim.ConstructorWrappers.leafCtrlBind;
import static net.sourceforge.vrapper.keymap.vim.ConstructorWrappers.operatorCmdsWithUpperCase;
import static net.sourceforge.vrapper.keymap.vim.ConstructorWrappers.state;
import static net.sourceforge.vrapper.keymap.vim.ConstructorWrappers.transitionBind;
import static net.sourceforge.vrapper.vim.commands.ConstructorWrappers.seq;
import net.sourceforge.vrapper.keymap.State;
import net.sourceforge.vrapper.keymap.vim.CountingState;
import net.sourceforge.vrapper.keymap.vim.GoThereState;
import net.sourceforge.vrapper.keymap.vim.RegisterState;
import net.sourceforge.vrapper.keymap.vim.TextObjectState;
import net.sourceforge.vrapper.utils.CaretType;
import net.sourceforge.vrapper.utils.LineInformation;
import net.sourceforge.vrapper.utils.Position;
import net.sourceforge.vrapper.vim.EditorAdaptor;
import net.sourceforge.vrapper.vim.Options;
import net.sourceforge.vrapper.vim.VimConstants;
import net.sourceforge.vrapper.vim.commands.BorderPolicy;
import net.sourceforge.vrapper.vim.commands.CenterLineCommand;
import net.sourceforge.vrapper.vim.commands.ChangeModeCommand;
import net.sourceforge.vrapper.vim.commands.ChangeOperation;
import net.sourceforge.vrapper.vim.commands.ChangeToInsertModeCommand;
import net.sourceforge.vrapper.vim.commands.Command;
import net.sourceforge.vrapper.vim.commands.DeleteOperation;
import net.sourceforge.vrapper.vim.commands.DotCommand;
import net.sourceforge.vrapper.vim.commands.InsertLineCommand;
import net.sourceforge.vrapper.vim.commands.LinewiseVisualMotionCommand;
import net.sourceforge.vrapper.vim.commands.MotionCommand;
import net.sourceforge.vrapper.vim.commands.MotionPairTextObject;
import net.sourceforge.vrapper.vim.commands.MotionTextObject;
import net.sourceforge.vrapper.vim.commands.OptionDependentTextObject;
import net.sourceforge.vrapper.vim.commands.PasteAfterCommand;
import net.sourceforge.vrapper.vim.commands.PasteBeforeCommand;
import net.sourceforge.vrapper.vim.commands.PlaybackMacroCommand;
import net.sourceforge.vrapper.vim.commands.RecordMacroCommand;
import net.sourceforge.vrapper.vim.commands.RedoCommand;
import net.sourceforge.vrapper.vim.commands.ReplaceCommand;
import net.sourceforge.vrapper.vim.commands.StickToEOLCommand;
import net.sourceforge.vrapper.vim.commands.SwapCaseCommand;
import net.sourceforge.vrapper.vim.commands.TextObject;
import net.sourceforge.vrapper.vim.commands.TextOperation;
import net.sourceforge.vrapper.vim.commands.TextOperationTextObjectCommand;
import net.sourceforge.vrapper.vim.commands.UndoCommand;
import net.sourceforge.vrapper.vim.commands.YankOperation;
import net.sourceforge.vrapper.vim.commands.motions.LineEndMotion;
import net.sourceforge.vrapper.vim.commands.motions.LineStartMotion;
import net.sourceforge.vrapper.vim.commands.motions.Motion;
import net.sourceforge.vrapper.vim.commands.motions.MoveLeft;
import net.sourceforge.vrapper.vim.commands.motions.MoveRight;
import net.sourceforge.vrapper.vim.commands.motions.MoveWordEndRight;
import net.sourceforge.vrapper.vim.commands.motions.MoveWordLeft;
import net.sourceforge.vrapper.vim.commands.motions.MoveWordRight;

public class NormalMode extends CommandBasedMode {

    public static final String KEYMAP_NAME = "Normal Mode Keymap";
    public static final String NAME = "normal mode";


    public NormalMode(EditorAdaptor editorAdaptor) {
        super(editorAdaptor);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected KeyMapResolver buildKeyMapResolver() {
        State<String> state = union(
                state(
                    leafBind('r', KeyMapResolver.NO_KEYMAP),
                    leafBind('z', KeyMapResolver.NO_KEYMAP),
                    leafBind('q', KeyMapResolver.NO_KEYMAP),
                    leafBind('@', KeyMapResolver.NO_KEYMAP)),
                getKeyMapsForMotions(),
                editorAdaptor.getPlatformSpecificStateProvider().getKeyMaps(NAME));
        final State<String> countEater = new CountConsumingState(state);
        State<String> registerKeymapState = new RegisterKeymapState(KEYMAP_NAME, countEater);
        return new KeyMapResolver(registerKeymapState, KEYMAP_NAME);
    }

    public static State<TextObject> textObjects() {
        final Motion wordRight = new MoveWordRight();
        final Motion wordLeft = new MoveWordLeft();
        final Motion wordEndRight = new MoveWordEndRight();
        final TextObject innerWord = new MotionPairTextObject(wordLeft, wordEndRight);
        final TextObject aWord = new MotionPairTextObject(wordLeft, wordRight);
        @SuppressWarnings("unchecked")
        State<TextObject> textObjects = union(
                state(
                        transitionBind('i',
                                leafBind('w', innerWord)),
                                transitionBind('a',
                                        leafBind('w', aWord))),
                                        new TextObjectState(motions()));

        textObjects = CountingState.wrap(textObjects);
        return textObjects;
    }

    @Override
    protected State<Command> getInitialState() {
        Command visualMode = new ChangeModeCommand(VisualMode.NAME);
        Command linewiseVisualMode = new ChangeModeCommand(LinewiseVisualMode.NAME);


        final Motion moveLeft = new MoveLeft();
        final Motion moveRight = new MoveRight();
        final Motion wordRight = new MoveWordRight();
        final Motion wordEndRight = new MoveWordEndRight();
        final Motion bol = new LineStartMotion(true);
        final Motion eol = new LineEndMotion(BorderPolicy.EXCLUSIVE);

        final State<Motion> motions = motions();
        final TextObject wordForCW = new OptionDependentTextObject(Options.STUPID_CW, wordEndRight, wordRight);
        final TextObject toEol = new MotionTextObject(eol);
        final TextObject wholeLine = new MotionTextObject(new LineEndMotion(BorderPolicy.LINE_WISE));
        final TextObject toEolForY = new OptionDependentTextObject(Options.STUPID_Y, wholeLine, toEol);

        State<TextObject> textObjects = textObjects();
        @SuppressWarnings("unchecked")
        State<TextObject> textObjectsForChange = CountingState.wrap(union(state(leafBind('w', wordForCW)), textObjects));

        TextOperation delete = new DeleteOperation();
        TextOperation change = new ChangeOperation();
        TextOperation yank   = new YankOperation();
        Command undo = new UndoCommand();
        Command redo = new RedoCommand();
        Command pasteAfter  = new PasteAfterCommand();
        Command pasteBefore = new PasteBeforeCommand();
        Command deleteNext = new TextOperationTextObjectCommand(delete, new MotionTextObject(moveRight));
        Command deletePrevious = new TextOperationTextObjectCommand(delete, new MotionTextObject(moveLeft));
        Command repeatLastOne = new DotCommand();
        Command tildeCmd = new SwapCaseCommand();
        Command stickToEOL = new StickToEOLCommand();
        LineEndMotion lineEndMotion = new LineEndMotion(BorderPolicy.LINE_WISE);
        Command substituteLine = new TextOperationTextObjectCommand(change, new MotionTextObject(lineEndMotion));
        Command substituteChar = new TextOperationTextObjectCommand(change, new MotionTextObject(moveRight));
        Command centerLine = new CenterLineCommand();

        State<Command> motionCommands = new GoThereState(motions);

        State<Command> platformSpecificState = getPlatformSpecificState(NAME);
        @SuppressWarnings("unchecked")
        State<Command> commands = new RegisterState(CountingState.wrap(union(
                operatorCmdsWithUpperCase('d', delete, toEol,     textObjects),
                operatorCmdsWithUpperCase('y', yank,   toEolForY, textObjects),
                operatorCmdsWithUpperCase('c', change, toEol,     textObjectsForChange),
                state(leafBind('$', stickToEOL)),
                motionCommands,
                state(
                        leafBind('i', (Command) new ChangeToInsertModeCommand()),
                        leafBind('a', (Command) new ChangeToInsertModeCommand(new MotionCommand(moveRight))),
                        leafBind('I', (Command) new ChangeToInsertModeCommand(new MotionCommand(bol))),
                        leafBind('A', (Command) new ChangeToInsertModeCommand(new MotionCommand(eol))),
                        leafBind(':', (Command) new ChangeModeCommand(CommandLineMode.NAME)),
                        leafBind('?', (Command) new ChangeModeCommand(SearchMode.NAME, SearchMode.Direction.BACKWARD)),
                        leafBind('/', (Command) new ChangeModeCommand(SearchMode.NAME, SearchMode.Direction.FORWARD)),
                        leafBind('R', (Command) new ChangeModeCommand(ReplaceMode.NAME)),
                        leafBind('o', (Command) new ChangeToInsertModeCommand(new InsertLineCommand(InsertLineCommand.Type.POST_CURSOR))),
                        leafBind('O', (Command) new ChangeToInsertModeCommand(new InsertLineCommand(InsertLineCommand.Type.PRE_CURSOR))),
                        leafBind('v', visualMode),
                        leafBind('V', seq(linewiseVisualMode, new LinewiseVisualMotionCommand(moveRight))),
                        leafBind('p', pasteAfter),
                        leafBind('.', repeatLastOne),
                        leafBind('P', pasteBefore),
                        leafBind('x', deleteNext),
                        leafBind('X', deletePrevious),
                        leafBind('~', tildeCmd),
                        leafBind('S', substituteLine),
                        leafBind('s', substituteChar),
                        transitionBind('q',
                                convertKeyStroke(
                                        RecordMacroCommand.KEYSTROKE_CONVERTER,
                                        VimConstants.PRINTABLE_KEYSTROKES)),
                        transitionBind('@',
                                convertKeyStroke(
                                        PlaybackMacroCommand.KEYSTROKE_CONVERTER,
                                        VimConstants.PRINTABLE_KEYSTROKES)),
                        transitionBind('r', changeCaret(CaretType.UNDERLINE),
                                convertKeyStroke(
                                        ReplaceCommand.KEYSTROKE_CONVERTER,
                                        VimConstants.PRINTABLE_KEYSTROKES)),
                        leafBind('u', undo),
                        leafCtrlBind('r', redo),
                        transitionBind('z',
                                leafBind('z', centerLine))),
                platformSpecificState)));

        return commands;
    }

    @Override
    protected void placeCursor() {
        Position pos = editorAdaptor.getPosition();
        int offset = pos.getViewOffset();
        LineInformation line = editorAdaptor.getViewContent().getLineInformationOfOffset(offset);
        if (isEnabled && line.getEndOffset() == offset && line.getLength() > 0) {
            editorAdaptor.setPosition(pos.addViewOffset(-1), false);
        }
    }

    @Override
    protected void commandDone() {
        super.commandDone();
        editorAdaptor.getCursorService().setCaret(CaretType.RECTANGULAR);
        editorAdaptor.getRegisterManager().activateDefaultRegister();
    }

    public void enterMode(Object... args) {
        if (isEnabled) {
            return;
        }
        isEnabled = true;
        placeCursor();
        editorAdaptor.getCursorService().setCaret(CaretType.RECTANGULAR);
    }

    @Override
    public void leaveMode() {
        super.leaveMode();
        if (!isEnabled) {
            return;
        }
        isEnabled = false;
    }

    public String getName() {
        return NAME;
    }
}
