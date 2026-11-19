// Run with generate.py. VectorCraft's ES5 bridge owns the documents and placement.
//@include "icons.js"
var args = vc.args;
var outline = 1.8;
var paper = {lined:[64,48], squared:[48,48], dots:[32,32], waves:[160,64]};
var seed = 509;
function random() {
    seed = (1664525 * seed + 1013904223) % 4294967296;
    return seed / 4294967296;
}
function run(id, params) { return vc.run(id, params || {}); }
function makeDocument(name, w, h, boards) {
    return run("file.new", {name:name, width:w, height:h, units:"Points", artboards:boards,
        artboardLayout:{layout:"row", spacing:200}, backgroundContents:"transparent"}).index;
}
function pathSvg(d, weight) {
    return '<svg xmlns="http://www.w3.org/2000/svg" width="28" height="28"><path d="' +
        d + '" fill="none" stroke="#000000" stroke-width="' + weight +
        '" stroke-linejoin="round" stroke-linecap="round"/></svg>';
}
function place(svg, name, x, y, angle) {
    run("clipboard.importSvg", {svg:svg, center:[x,y]});
    var ids = run("edit.pasteInPlace").ids;
    run("layer.setProps", {ids:ids, name:name});
    if (angle) { run("object.rotate", {ids:ids, angle:angle, origin:[x,y]}); }
}
function makeLibrary() {
    var themes = Object.keys(motifs);
    makeDocument("keepIT icon library", 250, themes.length * 48, 1);
    for (var row = 0; row < themes.length; row++) {
        var theme = themes[row], names = Object.keys(motifs[theme]);
        for (var col = 0; col < names.length; col++) {
            var name = names[col];
            place(pathSvg(motifs[theme][name], outline), theme + "-" + name,
                25 + 50 * col, 24 + 48 * row, 0);
        }
    }
}
function readTheme(name) {
    // The open native library supplies the editable geometry for each placement.
    var children = run("document.inspect", {depth:1, childLimit:500}).layers[0].children;
    var shapes = {};
    for (var i = 0; i < children.length; i++) {
        var child = children[i], prefix = name + "-";
        if (child.name.indexOf(prefix) === 0) {
            run("select.set", {ids:[child.id]});
            run("edit.copy");
            shapes[child.name.slice(prefix.length)] = run("clipboard.exportSvg").svg;
        }
    }
    return shapes;
}
function shuffled(names) {
    var copy = names.slice(0);
    for (var i = copy.length-1; i > 0; i--) {
        var j = Math.floor(random() * (i+1)), tmp = copy[i];
        copy[i] = copy[j]; copy[j] = tmp;
    }
    return copy;
}
function positions(name, names) {
    if (name === "pets") {
        return [["paw",99,0,11],["paw",81,40,14],["paw",98,80,4],
            ["paw",87,120,-10],["paw",115,160,-14],["paw",99,200,-5],
            ["paw",99,240,11],["bone",24,91,-13],["fish",178,42,10],
            ["yarn",196,143,-7],["fish",31,202,-12]];
    }
    var order = shuffled(names).concat(shuffled(names), shuffled(names));
    var marks = [];
    for (var i = 0; i < 9; i++) {
        var row = Math.floor(i/3), col = i%3;
        var x = 29 + 76*col + (row%2)*33 + (random()-0.5)*19;
        var y = 35 + 80*row + (random()-0.5)*27;
        marks.push([order[i], Math.round(x), Math.round(y), Math.round(random()*30)-15]);
    }
    return marks;
}
function pattern(name) {
    var size = paper[name] || [240,240];
    var shapes, marks;
    if (paper[name]) {
        var ruling = {
            lined:["M-8 24H72",2],
            squared:["M-8 24H56 M24 -8V56",2],
            dots:["M14 16h0.02",3],
            waves:["M-80 32q40 10 80 0t80 0t80 0t80 0",2]
        }[name];
        shapes = {paper:pathSvg(ruling[0],ruling[1])};
        marks = [["paper",size[0]/2,size[1]/2,0]];
    } else {
        shapes = readTheme(name);
        var names = Object.keys(motifs[name]);
        var index = Object.keys(motifs).indexOf(name);
        seed += 65537 * (index+1);
        marks = positions(name,names);
    }
    // One stencil in opaque black: the apps use only its alpha and paint it in the note's own
    // pattern colour (backgrounds/README.md), so opacity and theme variants are theirs to decide.
    makeDocument(name,size[0],size[1],1);
    run("artboard.rename",{index:0,name:"Stencil"});
    for (var i = 0; i < marks.length; i++) {
        var m = marks[i], svg = shapes[m[0]];
        if (!svg || svg.indexOf('stroke="#000000"') < 0) {
            throw new Error("Missing library outline for " + m[0]);
        }
        place(svg, m[0], m[1], m[2], m[3]);
    }
}
if (args.name === "icons") { makeLibrary(); } else { pattern(args.name); }
