function ThreeJsViewer(){

        this.SELECTED_COLOR = 0xffff00;
        this.UNSELECTED_COLOR = 0xFF0000;
        this.meshes = {};

        this.init = function(modelUrl) {

            this.projector = new THREE.Projector();
            this.size = {width: window.innerWidth - 1, height: window.innerHeight - 1}

            this.renderer = new THREE.WebGLRenderer();
            this.renderer.sortObjects = false;
            this.renderer.setSize( this.size.width, this.size.height);

            this.camera = new THREE.TrackballCamera({

                        fov: 50,
                        aspect: this.size.width / this.size.height,
                        near: 1,
                        far: 100000,

                        rotateSpeed: 1.0,
                        zoomSpeed: 1.2,
                        panSpeed: 0.2,

                        noZoom: false,
                        noPan: false,

                        staticMoving: false,
                        dynamicDampingFactor: 0.3,

                        minDistance: 0.1,
                        maxDistance: 100000,

                        keys: [ 65, 83, 68 ], // [ rotateKey, zoomKey, panKey ],

                        domElement: this.renderer.domElement

                    });
            this.camera.up = new THREE.Vector3(0, 0, 1);
            this.camera.position = new THREE.Vector3(1, 1, 1);
            this.camera.target.position = new THREE.Vector3(0, 0, 0);
            this.camera.screen.width =  this.size.width;
            this.camera.screen.height = this.size.height;

            this.scene = new THREE.Scene();

            var light1 = new THREE.DirectionalLight(0xffffff, 2);
            light1.position.x = .5;
            light1.position.y = 1;
            light1.position.z = 2;
            light1.position.normalize();
            this.scene.addLight(light1);

            var light2 = new THREE.DirectionalLight(0x555555, 1);
            light2.position.x = - 2;
            light2.position.y = - 1;
            light2.position.z = - .5;
            light2.position.normalize();
            this.scene.addLight(light2);


            var geometryLoader = new THREE.JSONLoader(true);
            this.root = new THREE.Object3D();
            this.scene.addObject(this.root);

            var callback = function(partId) { return function(geometry) {
                var material = new THREE.MeshPhongMaterial({ color: this.UNSELECTED_COLOR });
                var mesh = new THREE.Mesh(geometry, material);
                mesh.doubleSided = false;
                this.root.addChild(mesh);
                this.meshes[mesh.geometry.id] = partId;
            }.bind(this); }.bind(this);

    		var texture_path = geometryLoader.extractUrlbase(modelUrl);
		    var worker = new Worker(modelUrl);
        	worker.onmessage = function ( event ) {
        	    $.each(event.data, function(index, modelPart){
            		geometryLoader.createModel( modelPart.geometry, callback(modelPart.id), texture_path );
        	    });
                var bb = this.computeBoundingBox();
                var ext = {x: bb.x[1] - bb.x[0], y: bb.y[1] - bb.y[0], z: bb.z[1] - bb.z[0]};
                // center mesh
                this.root.position.x = ext.x * -.5 - bb.x[0];
                this.root.position.y = ext.y * -.5 - bb.y[0];
                this.root.position.z = ext.z * -.5 - bb.z[0];

                var maxExtent = Math.max.apply(Math, [ext.x, ext.y, ext.z]);
                this.camera.position = new THREE.Vector3(maxExtent, maxExtent, maxExtent);

                // TODO: adjust clipping
		        geometryLoader.onLoadComplete();
        	}.bind(this);
	        geometryLoader.onLoadStart();
	        worker.postMessage( new Date().getTime() );

            this.renderer.domElement.addEventListener('mousedown', this.onMouseDown.bind(this), false);
            document.body.appendChild(this.renderer.domElement);
            this.onclick = function(){};
        };

        this.computeBoundingBox = function(){
            this.root.children[0].geometry.computeBoundingBox();
            var initialBB = this.root.children[0].geometry.boundingBox;
            var bb = {x: [initialBB.x[0], initialBB.x[1]], y: [initialBB.y[0], initialBB.y[1]], z: [initialBB.z[0], initialBB.z[1]]};
            THREE.SceneUtils.traverseHierarchy(this.root, function(object){
                object.geometry.computeBoundingBox();
                $.each(['x', 'y', 'z'], function(index, dimension){
                    bb[dimension][0] = Math.min(bb[dimension][0], object.geometry.boundingBox[dimension][0]);
                    bb[dimension][1] = Math.max(bb[dimension][1], object.geometry.boundingBox[dimension][1]);
                });

            });
            return bb;
        };

        this.onMouseDown = function(event) {
            event.preventDefault();

            var mouse = new THREE.Vector3(0, 0, 0);
            mouse.x = ( event.clientX / this.size.width ) * 2 - 1;
            mouse.y = - ( event.clientY / this.size.height ) * 2 + 1;

            this.projector.unprojectVector(mouse, this.camera);

            var ray = new THREE.Ray(this.camera.position, mouse.subSelf(this.camera.position).normalize());

            var intersects = ray.intersectScene(this.scene);
            if (intersects.length > 0) {
                if (this.selected != intersects[0].object) {
                    if (this.selected) this.selected.materials[0].color.setHex(this.UNSELECTED_COLOR);
                    this.selected = intersects[0].object;
                    this.selected.materials[0].color.setHex(this.SELECTED_COLOR);
                }
            } else {
                if (this.selected) this.selected.materials[0].color.setHex(this.UNSELECTED_COLOR);
                this.selected = null;
            }
            this.onClick(this.selected ? this.meshes[this.selected.geometry.id] : 'nothing');
        };

        this.animate = function() {
            // Include examples/js/RequestAnimationFrame.js for cross-browser compatibility.
            requestAnimationFrame(this.animate.bind(this));
            this.render();
        };

        this.render = function() {
            this.renderer.render(this.scene,this.camera);
        };
}
